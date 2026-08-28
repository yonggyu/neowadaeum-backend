package com.neowadaeum.authoring.review;

import com.neowadaeum.catalog.publish.StoryPublisher;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.common.spi.SafetyCategory;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 인간 검수 큐 (§8.3, R8.6~R8.8, B-55).
 *
 * <p><b>{@code public} 은 사람이 연다</b> (R8.6). 자동 검수는 통과시켜도 열지 않으므로 (B-54),
 * 이 문이 없으면 {@code public} 으로 제출된 작품은 <b>영원히 {@code in_review} 에 머문다.</b>
 *
 * <p><b>판정은 이력을 남기고 상태를 바꾼다 — 그 둘은 한 트랜잭션이다.</b> 나누면 "승인됐는데
 * 누가 승인했는지 모르는" 작품이나 <b>이력만 있고 열리지 않은</b> 작품이 남는다.
 *
 * <p><b>{@code pass} 는 곧 게시다</b> (R8.8) — 승인하면서 가시성을 열고 현재 버전을 가리킨다.
 *
 * <p><b>사유는 카테고리만이다</b> (R8.7). 검수자가 문장을 적을 자리는 {@code note} 이고,
 * 그것은 <b>작성자에게 가지 않는다</b> — 사람이 쓴 설명에는 걸린 표현이 그대로 들어가며,
 * 그것이 곧 우회 사전이 된다 (S-11).
 */
@Service
public class ReviewQueueService {

	/**
	 * 한 번에 보는 크기.
	 *
	 * <p><b>큐의 길이를 응답으로 알리지 않는다</b> (S-11) — 몇 건이 밀려 있는지는 검수 처리량을
	 * 드러내고, 그것을 알면 <b>큐가 길 때를 골라 제출할 수 있다.</b>
	 */
	private static final int PAGE_SIZE = 50;

	/** 검수를 마친 작품은 승인 상태가 된다 (R8.8). */
	private static final String APPROVED_STATUS = "approved";

	private static final String REJECTED_STATUS = "rejected";

	/**
	 * <b>통과하면 공개다.</b>
	 *
	 * <p>인간 검수 큐에 오는 길은 {@code public} 제출 하나뿐이므로 (R8.6, B-54), 승인이 여는
	 * 가시성도 그 하나다. 사후 검수(B-59)가 <b>이미 승인된 작품</b>을 큐에 올리기 시작하면
	 * 그때는 목표 가시성을 함께 날라야 한다 — 그 경로는 아직 없다.
	 */
	private static final String PUBLIC_VISIBILITY = "public";

	/** 반려된 작품은 아무에게도 보이지 않는다 (I-8). */
	private static final String PRIVATE_VISIBILITY = "private";

	private final StoryPublisher publisher;

	private final StoryReviewRepository reviews;

	private final Clock clock;

	private final TransactionTemplate transactions;

	public ReviewQueueService(StoryPublisher publisher, StoryReviewRepository reviews, Clock clock,
			PlatformTransactionManager catalogTransactionManager) {
		this.publisher = publisher;
		this.reviews = reviews;
		this.clock = clock;
		this.transactions = new TransactionTemplate(catalogTransactionManager);
	}

	/**
	 * 지금 볼 차례인 것들.
	 *
	 * <p><b>기다린 시각은 마지막 판정에서 온다</b> — 자동 검수가 통과시킨 순간이 곧 큐에 들어온
	 * 순간이다. 작품 생성 시각을 쓰면 <b>재검수가 몇 달을 기다린 것처럼 보인다.</b>
	 */
	public List<QueueItem> pending() {
		List<StoryPublisher.AwaitingReview> awaiting = this.publisher.storiesAwaitingReview(PAGE_SIZE);
		if (awaiting.isEmpty()) {
			return List.of();
		}
		Map<UUID, Instant> queuedAt = latestReviewTimes(
				awaiting.stream().map(StoryPublisher.AwaitingReview::storyId).toList());

		List<QueueItem> items = new ArrayList<>(awaiting.size());
		for (StoryPublisher.AwaitingReview story : awaiting) {
			items.add(new QueueItem(story.storyId(), story.title(),
					queuedAt.getOrDefault(story.storyId(), story.createdAt())));
		}
		return List.copyOf(items);
	}

	/**
	 * 판정한다 (R8.7, R8.8).
	 *
	 * <p><b>기다리는 중인 작품만 판정할 수 있다.</b> 이미 처리된 것을 다시 판정하면 두 검수자가
	 * 같은 작품을 각자 보고 <b>나중에 누른 쪽이 이긴다</b> — 그것은 판정이 아니라 경합이다.
	 *
	 * @param reviewerRef 검수자의 {@code player_ref}. <b>{@code user.id} 가 아니다</b> (§5.3) —
	 * catalog 는 Identity 스토어가 아니다
	 */
	public Decision decide(UUID reviewerRef, UUID storyId, ReviewVerdict verdict,
			List<SafetyCategory> reasons, String note) {
		return this.transactions.execute(status -> {
			StoryPublisher.StoryStatus stored = this.publisher.statusOf(storyId)
					.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
			if (!ReviewStatus.IN_REVIEW.columnValue().equals(stored.reviewStatus())) {
				throw new ApiException(ErrorCode.REVIEW_NOT_PENDING);
			}

			ReviewStatus next = applyTo(storyId, verdict);
			this.reviews.save(StoryReview.of(storyId, ReviewStage.HUMAN, verdict, reasonsJson(reasons),
					reviewerRef, note, Instant.now(this.clock)));
			return new Decision(storyId, next);
		});
	}

	/**
	 * 판정을 작품에 반영한다.
	 *
	 * <p><b>{@code hold} 는 아무것도 바꾸지 않는다</b> — 큐에 남는다. 그래도 이력은 남는다:
	 * "봤고 판단을 미뤘다"는 <b>아무도 보지 않았다</b>와 다른 사실이며, 미뤄진 채로 얼마나
	 * 오래 있었는지는 그 기록으로만 답할 수 있다.
	 */
	private ReviewStatus applyTo(UUID storyId, ReviewVerdict verdict) {
		return switch (verdict) {
			case PASS -> {
				// R8.8 — 승인이 곧 게시다. 열면서 현재 버전을 가리킨다.
				this.publisher.applyReview(storyId, APPROVED_STATUS, PUBLIC_VISIBILITY);
				this.publisher.markCurrent(storyId,
						this.publisher.latestVersionId(storyId)
								.orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR)));
				yield ReviewStatus.APPROVED;
			}
			case REJECT -> {
				this.publisher.applyReview(storyId, REJECTED_STATUS, PRIVATE_VISIBILITY);
				yield ReviewStatus.REJECTED;
			}
			case HOLD -> ReviewStatus.IN_REVIEW;
		};
	}

	/** 작품마다 가장 최근 판정 시각. 정렬이 이미 끝난 목록을 한 번만 훑는다. */
	private Map<UUID, Instant> latestReviewTimes(Collection<UUID> storyIds) {
		Map<UUID, Instant> latest = new HashMap<>();
		for (StoryReview review : this.reviews.findByStoryIdInOrderByReviewedAtDesc(storyIds)) {
			latest.putIfAbsent(review.getStoryId(), review.getReviewedAt());
		}
		return latest;
	}

	/** 카테고리만 담는다 (R8.7). 문자열을 그대로 받지 않는 것이 그 보장이다. */
	private static String reasonsJson(List<SafetyCategory> reasons) {
		if (reasons == null || reasons.isEmpty()) {
			return "[]";
		}
		return reasons.stream().map(SafetyCategory::wireValue)
				.collect(java.util.stream.Collectors.joining("\",\"", "[\"", "\"]"));
	}

	/**
	 * 큐에 걸린 작품 한 건.
	 *
	 * <p><b>작성자를 담지 않는다.</b> 검수는 <b>무엇이 쓰였는가</b>를 보는 일이고, 누가 썼는지가
	 * 함께 오면 그것이 판정에 섞인다.
	 */
	public record QueueItem(UUID storyId, String title, Instant queuedAt) {
	}

	/** 판정 결과. */
	public record Decision(UUID storyId, ReviewStatus reviewStatus) {
	}
}

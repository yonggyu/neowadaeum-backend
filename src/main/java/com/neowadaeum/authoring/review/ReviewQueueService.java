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
import org.springframework.data.domain.Limit;
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
 * <p><b>{@code suspend} 는 그 반대 방향이다</b> (§13-64). 자동 정지는 신고 임계가 하지만
 * (R8.9), 임계에 닿지 않은 것을 사람이 보고 내려야 할 때가 있다 — 그 손이 없으면 <b>임계만이
 * 유일하게 내리는 길</b>이 된다.
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
	 * <b>제출이 기다리던 것을 통과시키면 공개다.</b>
	 *
	 * <p>{@code in_review} 로 큐에 오는 길은 {@code public} 제출 하나뿐이므로 (R8.6, B-54),
	 * 승인이 여는 가시성도 그 하나다. 사후 검수(B-59)가 <b>이미 승인된 작품</b>을 그 상태로
	 * 올리기 시작하면 그때는 목표 가시성을 함께 날라야 한다 — 그 경로는 아직 없다.
	 *
	 * <p><b>다른 길로 온 것은 원래 자리로 돌아간다.</b> 정지(B-57)와 샘플링(B-59)은 가시성을
	 * 건드리지 않으므로 원래 값이 그대로 남아 있고, 통과는 <b>그것</b>으로 돌려놓는다 — 신고
	 * 하나로 내려간 {@code unlisted} 작품이나 <b>무작위로 뽑혔을 뿐인</b> 작품이 통과하면서
	 * 공개되면 그것은 복귀가 아니다.
	 */
	private static final String PUBLIC_VISIBILITY = "public";

	/** 반려된 작품은 아무에게도 보이지 않는다 (I-8). */
	private static final String PRIVATE_VISIBILITY = "private";

	private final StoryPublisher publisher;

	private final StoryReviewRepository reviews;

	private final StoryAppealRepository appeals;

	private final Clock clock;

	private final TransactionTemplate transactions;

	public ReviewQueueService(StoryPublisher publisher, StoryReviewRepository reviews,
			StoryAppealRepository appeals, Clock clock,
			PlatformTransactionManager catalogTransactionManager) {
		this.publisher = publisher;
		this.reviews = reviews;
		this.appeals = appeals;
		this.clock = clock;
		this.transactions = new TransactionTemplate(catalogTransactionManager);
	}

	/**
	 * 지금 볼 차례인 것들.
	 *
	 * <p><b>기다린 시각은 마지막 판정에서 온다</b> — 자동 검수가 통과시킨 순간이 곧 큐에 들어온
	 * 순간이다. 작품 생성 시각을 쓰면 <b>재검수가 몇 달을 기다린 것처럼 보인다.</b>
	 *
	 * <p><b>이의는 순서를 바꾸지 않는다</b> (#290, §13-59). 요청은 공짜이므로 앞세우면 줄을 사는
	 * 길이 되고, <b>아직 아무도 보지 못한</b> 작품들이 그만큼 밀린다. 바뀌는 것은 표시 하나다.
	 */
	public List<QueueItem> pending() {
		List<StoryPublisher.AwaitingReview> awaiting =
				new ArrayList<>(this.publisher.storiesAwaitingReview(PAGE_SIZE));
		awaiting.addAll(sampledStories(PAGE_SIZE - Math.min(PAGE_SIZE, awaiting.size())));
		if (awaiting.isEmpty()) {
			return List.of();
		}
		List<UUID> storyIds = awaiting.stream().map(StoryPublisher.AwaitingReview::storyId).toList();
		Map<UUID, Instant> queuedAt = latestReviewTimes(storyIds);
		java.util.Set<UUID> appealed =
				java.util.Set.copyOf(this.appeals.storyIdsWithOpenAppeal(storyIds));

		List<QueueItem> items = new ArrayList<>(awaiting.size());
		for (StoryPublisher.AwaitingReview story : awaiting) {
			items.add(new QueueItem(story.storyId(), story.title(),
					ReviewStatus.valueOf(story.reviewStatus().toUpperCase(java.util.Locale.ROOT)),
					queuedAt.getOrDefault(story.storyId(), story.createdAt()),
					appealed.contains(story.storyId())));
		}
		return List.copyOf(items);
	}

	/**
	 * 판정한다 (R8.7, R8.8).
	 *
	 * <p><b>기다리는 중인 작품만 판정할 수 있다.</b> 이미 처리된 것을 다시 판정하면 두 검수자가
	 * 같은 작품을 각자 보고 <b>나중에 누른 쪽이 이긴다</b> — 그것은 판정이 아니라 경합이다.
	 *
	 * <p><b>{@code suspend} 만 그 조건이 다르다</b> (§13-64). 수동 정지의 대상은 <b>이미
	 * 승인돼 돌아다니는</b> 작품이고, 그런 작품은 정의상 큐에 없다 — 큐 안에서만 허용하면
	 * 사후 샘플링(R8.11)이 뽑아 준 것만 내릴 수 있게 되고, <b>무엇을 내릴지를 난수가 고르는</b>
	 * 셈이 된다.
	 *
	 * @param reviewerRef 검수자의 {@code player_ref}. <b>{@code user.id} 가 아니다</b> (§5.3) —
	 * catalog 는 Identity 스토어가 아니다
	 */
	public Decision decide(UUID reviewerRef, UUID storyId, ReviewVerdict verdict,
			List<SafetyCategory> reasons, String note) {
		return this.transactions.execute(status -> {
			StoryPublisher.StoryStatus stored = this.publisher.statusOf(storyId)
					.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
			if (!judgeable(storyId, stored.reviewStatus(), verdict)) {
				throw new ApiException(ErrorCode.REVIEW_NOT_PENDING);
			}

			ReviewStatus next = applyTo(storyId, stored, verdict);
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
	private ReviewStatus applyTo(UUID storyId, StoryPublisher.StoryStatus stored,
			ReviewVerdict verdict) {
		return switch (verdict) {
			case PASS -> {
				// R8.8 — 승인이 곧 게시다. 열면서 현재 버전을 가리킨다.
				this.publisher.applyReview(storyId, APPROVED_STATUS, restoredVisibilityOf(stored));
				this.publisher.markCurrent(storyId,
						this.publisher.latestVersionId(storyId)
								.orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR)));
				yield ReviewStatus.APPROVED;
			}
			case REJECT -> {
				// #245 · #249 — 승격과 재제출은 **이미 게시돼 있던** 작품을 in_review 로 되돌린
				// 것이고, 반려는 그 요청을 거절하는 일이지 원래 갖고 있던 공개를 빼앗는 일이
				// 아니다. 있던 자리로 돌아간다 (§13-42, §13-48, §13-50).
				if (aReReviewOfAPublishedStory(stored)) {
					this.publisher.applyReview(storyId, APPROVED_STATUS, stored.visibility());
					yield ReviewStatus.APPROVED;
				}
				this.publisher.applyReview(storyId, REJECTED_STATUS, PRIVATE_VISIBILITY);
				yield ReviewStatus.REJECTED;
			}
			case HOLD -> ReviewStatus.valueOf(stored.reviewStatus().toUpperCase(java.util.Locale.ROOT));
			case SUSPEND -> {
				// §13-64 · §13-41 — 자동 정지가 쓰는 바로 그 자리다. 정지는 review_status 만
				// 바꾸고 **가시성을 건드리지 않는다** — 지워 버리면 사람이 통과시킨 뒤 어디로
				// 돌려놓아야 하는지를 알 수 없다.
				this.publisher.suspend(storyId);
				yield ReviewStatus.SUSPENDED;
			}
		};
	}

	/**
	 * 통과가 여는 가시성.
	 *
	 * <p><b>어디서 왔는지가 답을 정한다.</b> 제출이 기다리던 것은 {@code public} 을 원했고
	 * (R8.6), 정지에서 돌아오는 것은 <b>내려가기 전의 자리</b>로 돌아간다 (B-57) — 정지는
	 * 가시성을 지우지 않았으므로 그 값이 그대로 남아 있다.
	 */
	private static String restoredVisibilityOf(StoryPublisher.StoryStatus stored) {
		// in_review 로 오는 길은 public 제출 하나뿐이다 (§13-39). 나머지 — 정지(§13-41)와
		// 샘플링(§13-42) — 는 내려가지도 가려지지도 않았으므로 있던 자리가 곧 답이다.
		return ReviewStatus.IN_REVIEW.columnValue().equals(stored.reviewStatus())
				? PUBLIC_VISIBILITY : stored.visibility();
	}

	/**
	 * <b>이 검수는 이미 게시돼 있던 작품의 재검수인가</b> (#245, #249).
	 *
	 * <p>표식은 <b>남겨 둔 가시성</b>이다. 승격({@link StoryVisibilityService})과
	 * 재제출({@link SubmissionService})은 이미 승인된 작품을 {@code in_review} 로 되돌리면서
	 * <b>가시성을 지우지 않는다</b> — 지울 이유가 없기 때문이다. R2.3 의 조회 조건이
	 * {@code approved} <b>AND</b> {@code visibility <> private} 이므로 {@code review_status}
	 * 하나로 이미 가려진다.
	 *
	 * <p>반면 <b>처음 내는 작품</b>은 아무에게도 보인 적이 없어 남겨 둘 자리가 없고, 그래서
	 * {@code private} 으로 들어온다. 두 경우가 그 값에서 이미 갈라져 있다.
	 *
	 * <p><b>새 컬럼을 만들지 않은 이유가 이것이다.</b> 지금 답이 하나인 값을 위해 상태를 두 곳에
	 * 두면, 컬럼과 이력이 어긋났을 때 어느 쪽이 진실인지 매번 문제가 된다 (§13-39, §13-42).
	 */
	private static boolean aReReviewOfAPublishedStory(StoryPublisher.StoryStatus stored) {
		return ReviewStatus.IN_REVIEW.columnValue().equals(stored.reviewStatus())
				&& !PRIVATE_VISIBILITY.equals(stored.visibility());
	}

	/**
	 * <b>이 판정을 이 상태에 내릴 수 있는가</b> (§13-64).
	 *
	 * <p>판정 셋({@code pass} · {@code reject} · {@code hold})은 <b>큐에 걸린 것</b>에 대한
	 * 판단이므로 기다리는 중인 작품만 받는다. {@code suspend} 는 <b>이미 게시된 것</b>에 대한
	 * 판단이므로 조건이 반대다 — {@code approved} 인 작품만 내릴 수 있다.
	 *
	 * <p><b>이것이 409 규칙의 예외이면서도 안전한 이유:</b> 정지는 <b>한 방향으로만</b>
	 * 움직인다. 아무것도 열지 않고 아무 판정도 끝내지 않으며, 작품을 내리면서 <b>큐에
	 * 올려놓는다</b> — 다른 검수자가 보던 판단은 그대로 남아 있고 정지된 상태에서 이어서
	 * 내릴 수 있다. 409 가 막으려던 것은 <b>진 쪽이 자기 판정이 사라진 줄도 모르는</b>
	 * 경합인데, 여기서는 사라지는 판정이 없다.
	 *
	 * <p><b>{@code in_review} 를 정지시키지 않는 이유</b> — 그 작품은 아직 아무에게도 보인 적이
	 * 없어 내릴 것이 없고 (R2.3), 정지시키면 <b>"어디서 왔는가"라는 표식을 잃는다</b>
	 * (§13-48) — 통과가 {@code public} 을 열어야 할 작품이 있던 자리로 되돌아가게 된다.
	 * 열지 않기로 하는 판정은 {@code reject} 이다.
	 *
	 * <p><b>이미 내려간 것을 또 내리지 않는다</b> — 409 다. 멱등하게 받으면 append-only 이력에
	 * 정지 기록이 두 줄 남고 (I-5), 그것은 <b>두 번의 사건</b>으로 읽힌다. 이미 큐에 있으므로
	 * 그 작품에 남은 일은 {@code pass} 또는 {@code reject} 이지 정지가 아니다.
	 */
	private boolean judgeable(UUID storyId, String reviewStatus, ReviewVerdict verdict) {
		if (verdict == ReviewVerdict.SUSPEND) {
			return APPROVED_STATUS.equals(reviewStatus);
		}
		return awaitsAHuman(storyId, reviewStatus);
	}

	/**
	 * <b>사람을 기다리는 상태인가.</b>
	 *
	 * <p>둘이다 — 제출이 기다리는 것(R8.6)과 <b>신고로 내려간 것</b>(R8.9). 후자를 큐에서
	 * 빼면 자동으로 내린 작품을 아무도 다시 보지 않게 된다.
	 */
	private boolean awaitsAHuman(UUID storyId, String reviewStatus) {
		return ReviewStatus.IN_REVIEW.columnValue().equals(reviewStatus)
				|| ReviewStatus.SUSPENDED.columnValue().equals(reviewStatus)
				// R8.11 — 샘플링으로 올라온 작품은 승인 상태 그대로다 (§13-42). 상태만 보면
				// 판정할 수 없고, 판정할 수 없는 항목은 큐에 있어도 큐가 아니다.
				|| this.reviews.isFlaggedForReview(storyId);
	}

	/**
	 * 샘플링이 올려 둔 작품들 (R8.11, B-59, §13-42).
	 *
	 * <p><b>상태로 찾을 수 없다.</b> 샘플링은 작품을 내리지 않으므로 (§13-12 — 인기 있는 것과
	 * 위험한 것은 다르다) {@code approved} 그대로이며, 큐에 있다는 사실은 <b>검수 이력의
	 * 표식</b>에만 남는다.
	 *
	 * <p><b>상태로 이미 올라온 것 뒤에 붙는다.</b> 제출을 기다리는 작품과 신고로 내려간 작품이
	 * 먼저다 — 그 둘은 <b>사람이 볼 때까지 아무도 못 보거나 아무도 못 하는</b> 상태이고,
	 * 샘플링은 이미 게시돼 잘 돌아가는 작품이다.
	 */
	private List<StoryPublisher.AwaitingReview> sampledStories(int limit) {
		if (limit <= 0) {
			return List.of();
		}
		return this.publisher
				.storiesByIds(this.reviews.storyIdsFlaggedForReview(Limit.of(limit)));
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
	 *
	 * <p><b>왜 큐에 있는지는 담는다</b> (B-57). 제출을 기다리는 것과 신고로 내려간 것은
	 * 검수자가 <b>다르게 봐야 하는 일</b>이다 — 하나는 아직 아무도 못 본 작품이고, 다른
	 * 하나는 이미 사람들이 본 작품이다.
	 *
	 * @param appealed <b>작성자가 이 정지에 이의를 제기했다</b> (#290, §13-59). 사유는 담지
	 * 않는다 — 큐는 무엇을 볼 차례인지를 답하는 자리이고, 작성자가 쓴 글은 원고와 같은 문으로
	 * 본다 (S-5). <b>판정의 근거가 아니다</b>: 이의는 다시 보라는 요청이지 통과시킬 이유가
	 * 아니며, 제기하지 않은 작품도 똑같이 사람이 본다
	 */
	public record QueueItem(UUID storyId, String title, ReviewStatus reviewStatus, Instant queuedAt,
			boolean appealed) {
	}

	/** 판정 결과. */
	public record Decision(UUID storyId, ReviewStatus reviewStatus) {
	}
}

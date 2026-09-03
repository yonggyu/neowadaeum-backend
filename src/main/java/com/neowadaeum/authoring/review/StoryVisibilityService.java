package com.neowadaeum.authoring.review;

import com.neowadaeum.catalog.publish.StoryPublisher;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.common.spi.StoryReviewTimes;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 공개 범위 변경 (R8.6, §13-39, §13-42, B-55, #245).
 *
 * <p><b>승격은 검수를 열고 하향은 즉시 반영된다.</b> {@code public} 만 인간 검수를 요구하므로
 * (R8.6) 좁아지는 방향에는 사람이 필요 없다 — 이미 자동 검수를 지난 작품이고, 볼 수 있는
 * 사람이 줄어들 뿐이다.
 *
 * <p><b>승격 중에도 가시성을 지우지 않는다.</b> {@code review_status} 만 {@code in_review} 로
 * 되돌린다 — R2.3 의 조회 조건({@code approved} <b>AND</b> {@code visibility <> private})이
 * 이미 작품을 가리므로 검수 중 노출은 그것으로 닫힌다. {@code private} 로 함께 내리면
 * <b>반려된 작성자가 원래 갖고 있던 {@code unlisted} 공개까지 잃는다</b> — 반려는 승격을
 * 거절하는 일이지 이미 가진 것을 빼앗는 일이 아니다 (§13-42 의 "있던 자리로 돌아간다").
 *
 * <p><b>그 남겨 둔 가시성이 곧 이 경로의 표식이다.</b> 제출·재제출은 {@code public} 을 원할 때
 * {@code visibility} 를 {@code private} 로 저장하므로 (B-54), {@code in_review} 인데
 * {@code private} 이 아니면 <b>이미 게시돼 있던 작품의 승격</b>이다. {@link ReviewQueueService}
 * 의 반려가 그것을 보고 돌아갈 자리를 정한다 — 새 컬럼도 새 상태도 만들지 않았다.
 */
@Service
public class StoryVisibilityService {

	private final StoryPublisher publisher;

	private final StoryReviewRepository reviews;

	/** 신청·승인 시각은 검수 이력에서 온다 (§13-57, #290). 승격도 그 이력을 남기므로 새 회차다. */
	private final StoryReviewTimeline timeline;

	private final Clock clock;

	private final TransactionTemplate transactions;

	public StoryVisibilityService(StoryPublisher publisher, StoryReviewRepository reviews,
			StoryReviewTimeline timeline, Clock clock,
			PlatformTransactionManager catalogTransactionManager) {
		this.publisher = publisher;
		this.reviews = reviews;
		this.timeline = timeline;
		this.clock = clock;
		this.transactions = new TransactionTemplate(catalogTransactionManager);
	}

	/**
	 * 바꾼다.
	 *
	 * <p><b>승인된 작품만 바꿀 수 있다.</b> 검수를 기다리는 중이거나 반려·정지된 작품의
	 * 가시성을 작성자가 움직이면 <b>검수 결과를 작성자가 되돌리는</b> 길이 된다 (I-8).
	 *
	 * @param authorRef 요청자의 {@code player_ref}. <b>{@code user.id} 가 아니다</b> (§5.3)
	 * @throws ApiException {@code NOT_FOUND} — 없거나 <b>남의</b> 작품 (I-8). 존재를 알려 주지
	 * 않는 것이 원고와 같은 규칙이다
	 */
	public VisibilityOutcome change(UUID authorRef, UUID storyId, Visibility target) {
		return this.transactions.execute(status -> {
			StoryPublisher.OwnedStory stored = this.publisher.ownerStatusOf(storyId)
					.filter(owned -> owned.authorRef().equals(authorRef))
					.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));

			ReviewStatus reviewStatus = statusOf(stored.reviewStatus());
			Visibility current = visibilityOf(stored.visibility());
			if (reviewStatus != ReviewStatus.APPROVED) {
				throw new ApiException(ErrorCode.VALIDATION_ERROR,
						Map.of("reason", "story_not_approved"));
			}
			if (current == target) {
				return withTimes(new VisibilityOutcome(storyId, reviewStatus, current, null));
			}
			return withTimes((target == Visibility.PUBLIC) ? promote(storyId, current)
					: narrow(storyId, target));
		});
	}

	/**
	 * {@code unlisted} → {@code public} — <b>재검수를 연다</b> (R8.6, B-55 DoD).
	 *
	 * <p><b>{@code private} 에서는 올라올 수 없다.</b> 아무에게도 보인 적 없는 작품을 공개하는
	 * 것은 승격이 아니라 <b>제출</b>이고, 그 길은 {@code submit} 에 이미 있다 (§13-39 — "
	 * {@code in_review} 로 오는 길은 {@code public} 제출 하나"). 두 경로가 같은 상태를 서로 다른
	 * 뜻으로 쓰면 반려가 어디로 돌아가야 하는지 알 수 없어진다.
	 *
	 * <p><b>이력을 남긴다.</b> 큐는 <b>마지막 판정 시각</b>을 기다린 시각으로 쓰므로
	 * ({@link ReviewQueueService#pending()}), 남기지 않으면 승인받은 그날부터 기다린 것으로
	 * 보인다 — <b>재검수가 몇 달을 기다린 것처럼</b> 줄 앞에 선다.
	 */
	private VisibilityOutcome promote(UUID storyId, Visibility current) {
		if (current != Visibility.UNLISTED) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR,
					Map.of("reason", "promote_requires_unlisted"));
		}
		this.publisher.applyReview(storyId, ReviewStatus.IN_REVIEW.columnValue(),
				current.columnValue());
		this.reviews.save(StoryReview.of(storyId, ReviewStage.AUTO, ReviewVerdict.PASS, "[]", null,
				null, Instant.now(this.clock)));
		return new VisibilityOutcome(storyId, ReviewStatus.IN_REVIEW, current, null);
	}

	/**
	 * 좁히는 방향 — 사람이 필요 없다 (R8.6).
	 *
	 * <p>{@code public} → {@code unlisted} · {@code private}, {@code unlisted} →
	 * {@code private} 이 여기로 온다. <b>검수 상태는 그대로다</b> — 이미 승인된 작품이고,
	 * 다시 넓힐 때 {@link #promote} 가 사람을 부른다.
	 */
	private VisibilityOutcome narrow(UUID storyId, Visibility target) {
		this.publisher.applyReview(storyId, ReviewStatus.APPROVED.columnValue(),
				target.columnValue());
		return new VisibilityOutcome(storyId, ReviewStatus.APPROVED, target, null);
	}

	/**
	 * 검수 시각을 얹는다 (§13-57).
	 *
	 * <p><b>쓰고 나서 읽는다.</b> 승격이 남긴 기록이 새 회차의 시작이므로, 값을 여기서 따로
	 * 짓지 않고 이력에서 읽어야 목록·상세·이 응답이 같은 날짜를 말한다.
	 */
	private VisibilityOutcome withTimes(VisibilityOutcome outcome) {
		return new VisibilityOutcome(outcome.storyId(), outcome.reviewStatus(), outcome.visibility(),
				this.timeline.of(outcome.storyId()));
	}

	private static ReviewStatus statusOf(String columnValue) {
		return ReviewStatus.valueOf(columnValue.toUpperCase(Locale.ROOT));
	}

	private static Visibility visibilityOf(String columnValue) {
		return Visibility.valueOf(columnValue.toUpperCase(Locale.ROOT));
	}

	/**
	 * 변경 후 상태.
	 *
	 * <p><b>반려 사유를 담지 않는다.</b> 이 요청은 판정이 아니라 요청이며, 사유가 생기는 자리는
	 * 검수다 (R8.7).
	 */
	public record VisibilityOutcome(UUID storyId, ReviewStatus reviewStatus, Visibility visibility,
			StoryReviewTimes times) {
	}
}

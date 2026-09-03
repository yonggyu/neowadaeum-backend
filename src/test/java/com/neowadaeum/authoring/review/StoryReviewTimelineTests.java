package com.neowadaeum.authoring.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.neowadaeum.common.spi.StoryReviewTimes;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * §13-57 (#290) — <b>신청 시각과 승인 시각은 검수 이력에서 나온다.</b>
 *
 * <p>여기서 지키는 것은 <b>회차를 가르는 규칙</b> 하나다. 같은 작품에 검수가 여러 번 일어나므로
 * (재제출 · 승격 · 신고 · 재스캔), 지난 회차의 판정이 이번 신청 옆에 붙으면 화면은 <b>"검수
 * 대기"라고 적으면서 승인 날짜를 함께 보여 준다.</b>
 *
 * <p>DB 가 필요 없다 — 규칙은 <b>기록의 순서와 종류</b>만 본다. 실제 이력이 그렇게 쌓이는지는
 * {@code ReviewTimestampIntegrationTests} 가 서비스 경로로 확인한다.
 *
 * <p><b>S-11 — 픽스처는 전부 가상의 값이다.</b>
 */
class StoryReviewTimelineTests {

	private static final UUID STORY = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");

	private static final Instant FIRST_SUBMIT = Instant.parse("2026-02-18T00:00:00Z");

	private static final Instant FIRST_VERDICT = Instant.parse("2026-02-19T00:00:00Z");

	private static final Instant RESUBMIT = Instant.parse("2026-02-21T00:00:00Z");

	private static final Instant SECOND_VERDICT = Instant.parse("2026-02-23T00:00:00Z");

	private final StoryReviewRepository reviews = mock(StoryReviewRepository.class);

	private final StoryReviewTimeline timeline = new StoryReviewTimeline(this.reviews);

	/** 검수 이력이 하나도 없으면 두 값 모두 {@code null} 이다 — 지어낸 시각을 넣지 않는다. */
	@Test
	void S13_57_a_story_with_no_review_history_has_neither_time() {
		givenHistory();

		assertThat(this.timeline.of(STORY)).isEqualTo(StoryReviewTimes.NONE);
	}

	/**
	 * 제출 직후 — <b>신청만 있고 승인은 없다.</b>
	 *
	 * <p>기다리는 사람에게 필요한 것이 이 상태다: 언제부터 세는지는 있고, 답은 아직 없다.
	 */
	@Test
	void S13_57_right_after_a_submission_only_the_request_has_a_time() {
		givenHistory(auto(ReviewVerdict.PASS, FIRST_SUBMIT));

		assertThat(this.timeline.of(STORY))
				.isEqualTo(new StoryReviewTimes(FIRST_SUBMIT, null));
	}

	/** 사람이 판정하면 둘 다 있다 — 같은 회차의 신청과 답이다. */
	@Test
	void S13_57_a_human_verdict_fills_the_reviewed_time_of_the_same_round() {
		givenHistory(human(ReviewVerdict.PASS, FIRST_VERDICT), auto(ReviewVerdict.PASS, FIRST_SUBMIT));

		assertThat(this.timeline.of(STORY))
				.isEqualTo(new StoryReviewTimes(FIRST_SUBMIT, FIRST_VERDICT));
	}

	/**
	 * 재제출하면 회차가 새로 열린다 — <b>신청은 새 시각이고 승인은 다시 비어 있다.</b>
	 *
	 * <p>지난 회차의 승인을 남겨 두면 <b>이미 답이 나온 것처럼</b> 보인다. 아직 답이 없다는
	 * 것이 사실이다.
	 */
	@Test
	void S13_57_a_resubmission_opens_a_new_round_and_clears_the_previous_verdict() {
		givenHistory(auto(ReviewVerdict.PASS, RESUBMIT), human(ReviewVerdict.PASS, FIRST_VERDICT),
				auto(ReviewVerdict.PASS, FIRST_SUBMIT));

		assertThat(this.timeline.of(STORY)).isEqualTo(new StoryReviewTimes(RESUBMIT, null));
	}

	/** 새 회차가 판정되면 그 회차의 답이 온다. 지난 회차의 것이 아니다. */
	@Test
	void S13_57_the_verdict_of_the_current_round_wins_over_the_previous_one() {
		givenHistory(human(ReviewVerdict.REJECT, SECOND_VERDICT), auto(ReviewVerdict.PASS, RESUBMIT),
				human(ReviewVerdict.PASS, FIRST_VERDICT), auto(ReviewVerdict.PASS, FIRST_SUBMIT));

		assertThat(this.timeline.of(STORY))
				.isEqualTo(new StoryReviewTimes(RESUBMIT, SECOND_VERDICT));
	}

	/**
	 * <b>재스캔과 샘플링은 신청이 아니다</b> (B-59, R8.11, §13-42).
	 *
	 * <p>작성자가 부른 적이 없는 기록이 회차를 열면, 자동으로 내려간 작품의 화면이 <b>방금
	 * 신청한 것처럼</b> 보인다.
	 */
	@Test
	void S13_57_an_automatic_rescan_or_sampling_record_does_not_open_a_round() {
		givenHistory(auto(ReviewVerdict.HOLD, SECOND_VERDICT), auto(ReviewVerdict.REJECT, RESUBMIT),
				human(ReviewVerdict.PASS, FIRST_VERDICT), auto(ReviewVerdict.PASS, FIRST_SUBMIT));

		assertThat(this.timeline.of(STORY))
				.isEqualTo(new StoryReviewTimes(FIRST_SUBMIT, FIRST_VERDICT));
	}

	/**
	 * <b>§15 — 목록이 길어져도 조회는 하나다.</b>
	 *
	 * <p>작품마다 물으면 20줄이 21번의 조회가 된다. 그것이 이 필드를 더하면서 가장 쉽게
	 * 저지르는 실수이므로 <b>몇 번 물었는지</b>를 센다.
	 */
	@Test
	void S15_a_page_of_stories_costs_one_query() {
		UUID other = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000002");
		when(this.reviews.findByStoryIdInOrderByReviewedAtDesc(any()))
				.thenReturn(List.of(auto(ReviewVerdict.PASS, RESUBMIT),
						review(other, ReviewStage.HUMAN, ReviewVerdict.PASS, FIRST_VERDICT),
						review(other, ReviewStage.AUTO, ReviewVerdict.PASS, FIRST_SUBMIT)));

		Map<UUID, StoryReviewTimes> times = this.timeline.findByStoryIds(List.of(STORY, other));

		assertThat(times).containsOnlyKeys(STORY, other);
		assertThat(times.get(STORY)).isEqualTo(new StoryReviewTimes(RESUBMIT, null));
		assertThat(times.get(other)).isEqualTo(new StoryReviewTimes(FIRST_SUBMIT, FIRST_VERDICT));
		verify(this.reviews).findByStoryIdInOrderByReviewedAtDesc(any());
	}

	/** 빈 목록은 묻지 않는다 — {@code IN ()} 은 질의가 아니다. */
	@Test
	void S15_an_empty_page_asks_nothing() {
		assertThat(this.timeline.findByStoryIds(List.of())).isEmpty();
		verify(this.reviews, org.mockito.Mockito.never())
				.findByStoryIdInOrderByReviewedAtDesc(org.mockito.ArgumentMatchers.anyCollection());
	}

	private void givenHistory(StoryReview... descending) {
		when(this.reviews.findByStoryIdOrderByReviewedAtDesc(eq(STORY)))
				.thenReturn(List.of(descending));
	}

	private static StoryReview auto(ReviewVerdict verdict, Instant at) {
		return review(STORY, ReviewStage.AUTO, verdict, at);
	}

	private static StoryReview human(ReviewVerdict verdict, Instant at) {
		return review(STORY, ReviewStage.HUMAN, verdict, at);
	}

	private static StoryReview review(UUID storyId, ReviewStage stage, ReviewVerdict verdict,
			Instant at) {
		UUID reviewer = (stage == ReviewStage.HUMAN)
				? UUID.fromString("bbbbbbbb-0000-4000-8000-000000000001") : null;
		return StoryReview.of(storyId, stage, verdict, "[]", reviewer, null, at);
	}

}

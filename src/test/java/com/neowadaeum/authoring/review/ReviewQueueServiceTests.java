package com.neowadaeum.authoring.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.authoring.draft.DraftService;
import com.neowadaeum.authoring.draft.StoryDraftRepository;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.common.spi.SafetyCategory;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * B-55 — <b>{@code public} 은 사람이 연다</b> (R8.6), 그리고 <b>승인이 곧 게시다</b> (R8.8).
 *
 * <p><b>S-11 — 픽스처는 전부 가상의 문자열이다.</b>
 */
class ReviewQueueServiceTests extends ContainerTestBase {

	private static final String PAYLOAD = """
			{"title":"봄의 학교","shortDesc":"짧은 소개","worldIntro":"소개",
			 "worldPrompt":"봄의 학교에서 시작한다.",
			 "chapters":[{"title":"1장","summarySeed":"시작"}],
			 "endings":[{"label":"좋은 끝","epilogueText":"잘 끝났다."}]}
			""";

	private static final UUID REVIEWER_REF = UUID.fromString("00000000-0000-4000-8000-0000000000e1");

	@Autowired
	private ReviewQueueService queue;

	@Autowired
	private SubmissionService submissions;

	@Autowired
	private DraftService drafts;

	@Autowired
	private StoryDraftRepository draftRows;

	@Autowired
	private StoryReviewRepository reviews;

	@Autowired
	@Qualifier("catalogDataSource")
	private DataSource catalog;

	private final List<UUID> stories = new java.util.ArrayList<>();

	@AfterEach
	void clear() {
		JdbcClient jdbc = JdbcClient.create(this.catalog);
		this.reviews.deleteAll();
		for (UUID storyId : this.stories) {
			jdbc.sql("DELETE FROM chapter_def WHERE story_id = ?").param(storyId).update();
			jdbc.sql("DELETE FROM ending_def WHERE story_id = ?").param(storyId).update();
			jdbc.sql("UPDATE story SET current_version_id = NULL WHERE id = ?").param(storyId).update();
			jdbc.sql("DELETE FROM story_version WHERE story_id = ?").param(storyId).update();
			jdbc.sql("DELETE FROM story WHERE id = ?").param(storyId).update();
		}
		this.stories.clear();
		this.draftRows.deleteAll();
	}

	/** 자동 검수를 지난 {@code public} 작품은 <b>큐에서 기다린다</b> (R8.6). */
	@Test
	void R8_6_a_public_submission_waits_in_the_queue() {
		UUID storyId = submit(Visibility.PUBLIC);

		assertThat(this.queue.pending()).extracting(ReviewQueueService.QueueItem::storyId)
				.contains(storyId);
	}

	/**
	 * <b>{@code unlisted} 는 큐에 오지 않는다</b> (R8.6).
	 *
	 * <p>자동 검수만으로 승인되므로 사람을 기다릴 것이 없다 — 큐에 섞이면 검수자가 <b>이미
	 * 게시된 작품</b>을 판정하게 된다.
	 */
	@Test
	void R8_6_an_unlisted_submission_does_not_reach_the_queue() {
		UUID storyId = submit(Visibility.UNLISTED);

		assertThat(this.queue.pending()).extracting(ReviewQueueService.QueueItem::storyId)
				.doesNotContain(storyId);
	}

	/**
	 * <b>{@code unlisted} 로 승인받은 뒤 {@code public} 으로 올리면 다시 사람을 기다린다</b>
	 * (§13-12, R8.6).
	 *
	 * <p>이것이 없으면 <b>자동 검수만 뚫고 공개 섹션에 오르는 길</b>이 열린다 — 공격자에게
	 * 최적 전략은 {@code unlisted} 로 게시한 뒤 승격하는 것이 된다.
	 */
	@Test
	void R8_6_promoting_to_public_triggers_a_human_review_again() {
		UUID draftId = givenDraft();
		UUID authorRef = authorOf(draftId);
		var first = this.submissions.submit(authorRef, draftId, Visibility.UNLISTED);
		this.stories.add(first.storyId());
		assertThat(first.reviewStatus()).isEqualTo(ReviewStatus.APPROVED);

		// 같은 원고를 public 으로 다시 낸다. (같은 작품에 새 버전을 얹는 것은 B-56 이다.)
		var promoted = this.submissions.submit(authorRef, draftId, Visibility.PUBLIC);
		this.stories.add(promoted.storyId());

		assertThat(promoted.reviewStatus()).isEqualTo(ReviewStatus.IN_REVIEW);
		assertThat(promoted.visibility()).isEqualTo(Visibility.PRIVATE);
		assertThat(this.queue.pending()).extracting(ReviewQueueService.QueueItem::storyId)
				.contains(promoted.storyId());
	}

	/** <b>승인이 곧 게시다</b> (R8.8) — 열면서 현재 버전을 가리킨다. */
	@Test
	void R8_8_a_pass_opens_the_story_and_makes_a_version_current() {
		UUID storyId = submit(Visibility.PUBLIC);

		var decision = this.queue.decide(REVIEWER_REF, storyId, ReviewVerdict.PASS, List.of(), null);

		assertThat(decision.reviewStatus()).isEqualTo(ReviewStatus.APPROVED);
		assertThat(column(storyId, "review_status")).isEqualTo("approved");
		assertThat(column(storyId, "visibility")).isEqualTo("public");
		assertThat(column(storyId, "current_version_id")).isNotNull();
		assertThat(this.queue.pending()).extracting(ReviewQueueService.QueueItem::storyId)
				.doesNotContain(storyId);
	}

	/** <b>반려된 작품은 아무에게도 보이지 않는다</b> (I-8) — 버전도 현재가 되지 않는다. */
	@Test
	void I8_a_rejected_story_stays_hidden() {
		UUID storyId = submit(Visibility.PUBLIC);

		var decision = this.queue.decide(REVIEWER_REF, storyId, ReviewVerdict.REJECT,
				List.of(SafetyCategory.RATING_EXCEEDED), "내부 기록");

		assertThat(decision.reviewStatus()).isEqualTo(ReviewStatus.REJECTED);
		assertThat(column(storyId, "review_status")).isEqualTo("rejected");
		assertThat(column(storyId, "visibility")).isEqualTo("private");
		assertThat(column(storyId, "current_version_id")).isNull();
	}

	/**
	 * <b>{@code hold} 는 큐에 남긴다</b> — 그래도 이력은 남는다.
	 *
	 * <p>"봤고 판단을 미뤘다"는 <b>아무도 보지 않았다</b>와 다른 사실이며, 얼마나 오래 미뤄져
	 * 있었는지는 그 기록으로만 답할 수 있다.
	 */
	@Test
	void R8_7_a_hold_changes_nothing_but_is_recorded() {
		UUID storyId = submit(Visibility.PUBLIC);

		var decision = this.queue.decide(REVIEWER_REF, storyId, ReviewVerdict.HOLD, List.of(), null);

		assertThat(decision.reviewStatus()).isEqualTo(ReviewStatus.IN_REVIEW);
		assertThat(column(storyId, "review_status")).isEqualTo("in_review");
		assertThat(this.queue.pending()).extracting(ReviewQueueService.QueueItem::storyId)
				.contains(storyId);
		assertThat(this.reviews.findByStoryIdOrderByReviewedAtDesc(storyId))
				.anySatisfy(review -> assertThat(review.getVerdict()).isEqualTo(ReviewVerdict.HOLD));
	}

	/** <b>누가 승인했는지 모르는 승인은 감사에 쓸모가 없다</b> — 인간 검수는 검수자를 남긴다. */
	@Test
	void R8_7_a_human_review_records_who_decided() {
		UUID storyId = submit(Visibility.PUBLIC);

		this.queue.decide(REVIEWER_REF, storyId, ReviewVerdict.PASS, List.of(), null);

		assertThat(this.reviews.findFirstByStoryIdOrderByReviewedAtDesc(storyId)).get()
				.satisfies(review -> {
					assertThat(review.getStage()).isEqualTo(ReviewStage.HUMAN);
					assertThat(review.getReviewerRef()).isEqualTo(REVIEWER_REF);
				});
	}

	/**
	 * <b>작성자가 보는 사유는 카테고리만이다</b> (R8.7, S-11).
	 *
	 * <p>검수자가 적은 {@code note} 는 작성자에게 가지 않는다 — 사람이 쓴 설명에는 걸린 표현이
	 * 그대로 들어가며, 그것이 곧 우회 사전이다.
	 */
	@Test
	void R8_7_the_author_sees_categories_but_not_the_reviewers_note() {
		UUID draftId = givenDraft();
		UUID authorRef = authorOf(draftId);
		var submitted = this.submissions.submit(authorRef, draftId, Visibility.PUBLIC);
		this.stories.add(submitted.storyId());
		String note = "이나린 이라는 이름이 걸렸다";

		this.queue.decide(REVIEWER_REF, submitted.storyId(), ReviewVerdict.REJECT,
				List.of(SafetyCategory.REAL_PERSON_HARM), note);

		var seen = this.submissions.reviewStatus(authorRef, draftId);
		assertThat(seen.reviewStatus()).isEqualTo(ReviewStatus.REJECTED);
		assertThat(seen.rejectReasons()).containsExactly("real_person_harm");
		assertThat(seen.rejectReasons()).allSatisfy(reason -> assertThat(reason).doesNotContain("이나린"));
	}

	/**
	 * <b>기다리는 중인 작품만 판정할 수 있다.</b>
	 *
	 * <p>두 검수자가 같은 작품을 각자 열어 두면 나중에 누른 쪽이 이긴다 — 그것은 판정이 아니라
	 * 경합이고, 진 쪽은 자기 판정이 사라진 줄도 모른다.
	 */
	@Test
	void R8_6_a_story_that_is_not_waiting_cannot_be_judged_twice() {
		UUID storyId = submit(Visibility.PUBLIC);
		this.queue.decide(REVIEWER_REF, storyId, ReviewVerdict.PASS, List.of(), null);

		assertThatThrownBy(
				() -> this.queue.decide(REVIEWER_REF, storyId, ReviewVerdict.REJECT, List.of(), null))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.REVIEW_NOT_PENDING);
	}

	/** 없는 작품은 404 다. */
	@Test
	void R8_6_an_unknown_story_is_not_found() {
		assertThatThrownBy(() -> this.queue.decide(REVIEWER_REF, UUID.randomUUID(), ReviewVerdict.PASS,
				List.of(), null))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.NOT_FOUND);
	}

	private UUID submit(Visibility visibility) {
		UUID draftId = givenDraft();
		var outcome = this.submissions.submit(authorOf(draftId), draftId, visibility);
		this.stories.add(outcome.storyId());
		return outcome.storyId();
	}

	private UUID givenDraft() {
		UUID authorRef = UUID.randomUUID();
		UUID draftId = this.drafts.create(authorRef).getId();
		this.drafts.save(authorRef, draftId, 5, PAYLOAD);
		return draftId;
	}

	private UUID authorOf(UUID draftId) {
		return this.draftRows.findById(draftId).orElseThrow().getAuthorRef();
	}

	private String column(UUID storyId, String name) {
		return JdbcClient.create(this.catalog).sql("SELECT " + name + "::text FROM story WHERE id = ?")
				.param(storyId).query(String.class).optional().orElse(null);
	}
}

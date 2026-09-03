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

	/** 타인이 보는 조회 경로다 — 정지가 실제로 문을 닫는지는 여기서만 확인된다 (I-8). */
	@Autowired
	private com.neowadaeum.catalog.query.StoryCatalogFacade catalogQueries;

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
		// #249 — 이미 승인돼 있던 가시성을 지우지 않는다. 검수 중 노출은 review_status 하나로
		// 이미 닫히고(R2.3), 지우면 반려됐을 때 돌아갈 자리가 없다.
		assertThat(promoted.visibility()).isEqualTo(Visibility.UNLISTED);
		assertThat(this.queue.pending()).extracting(ReviewQueueService.QueueItem::storyId)
				.contains(promoted.storyId());
	}

	/**
	 * <b>재제출 반려는 이미 갖고 있던 게시를 빼앗지 않는다</b> (#249, §13-50).
	 *
	 * <p>작성자가 오타 하나를 고쳤다가 반려되면 <b>고치기 전에 갖고 있던 게시까지</b> 잃던
	 * 상태였다. 개정판을 거절하는 것과 원본을 내리는 것은 다른 일이다 — 붙이면 <b>요청한
	 * 것보다 많은 것을 잃고</b>, 그러면 아무도 작품을 고치지 않게 된다.
	 */
	@Test
	void R8_8_rejecting_a_revision_returns_the_story_to_where_it_was() {
		UUID draftId = givenDraft();
		UUID authorRef = authorOf(draftId);
		var first = this.submissions.submit(authorRef, draftId, Visibility.UNLISTED);
		this.stories.add(first.storyId());
		var revision = this.submissions.submit(authorRef, draftId, Visibility.PUBLIC);

		var decision = this.queue.decide(REVIEWER_REF, revision.storyId(), ReviewVerdict.REJECT,
				List.of(SafetyCategory.RATING_EXCEEDED), null);

		assertThat(decision.reviewStatus()).isEqualTo(ReviewStatus.APPROVED);
		assertThat(column(revision.storyId(), "review_status")).isEqualTo("approved");
		assertThat(column(revision.storyId(), "visibility")).isEqualTo("unlisted");
	}

	/**
	 * <b>재검수 중에는 내려가 있다</b> (§13-40 이 받아들인 대가).
	 *
	 * <p>가시성을 남겨 두는 것이 <b>검수 중 노출</b>을 뜻하지 않는다. R2.3 의 타인 조회 조건이
	 * {@code approved} <b>AND</b> {@code visibility <> private} 이므로 {@code in_review} 하나로
	 * 이미 가려진다.
	 */
	@Test
	void R2_3_a_story_under_re_review_is_not_approved_anymore() {
		UUID draftId = givenDraft();
		UUID authorRef = authorOf(draftId);
		this.stories.add(this.submissions.submit(authorRef, draftId, Visibility.UNLISTED).storyId());
		var revision = this.submissions.submit(authorRef, draftId, Visibility.PUBLIC);

		assertThat(column(revision.storyId(), "review_status")).isEqualTo("in_review");
		assertThat(column(revision.storyId(), "visibility")).isNotEqualTo("public");
	}

	/** <b>재제출 승인은 {@code public} 을 연다</b> (§13-39) — 그것이 재제출의 목표였다. */
	@Test
	void R8_8_passing_a_revision_opens_public() {
		UUID draftId = givenDraft();
		UUID authorRef = authorOf(draftId);
		this.stories.add(this.submissions.submit(authorRef, draftId, Visibility.UNLISTED).storyId());
		var revision = this.submissions.submit(authorRef, draftId, Visibility.PUBLIC);

		this.queue.decide(REVIEWER_REF, revision.storyId(), ReviewVerdict.PASS, List.of(), null);

		assertThat(column(revision.storyId(), "review_status")).isEqualTo("approved");
		assertThat(column(revision.storyId(), "visibility")).isEqualTo("public");
	}

	/**
	 * <b>"있던 자리"는 승인돼 있던 자리다</b> (#249).
	 *
	 * <p>정지된 작품에서 낸 재제출까지 되돌리면 <b>반려가 정지를 푸는 길</b>이 된다 — 신고
	 * 누적으로 내려간 작품을 작성자가 재제출하고 검수자가 거절하기만 하면 복귀한다.
	 */
	@Test
	void R8_9_a_revision_from_suspension_does_not_inherit_a_place_to_return_to() {
		UUID draftId = givenDraft();
		UUID authorRef = authorOf(draftId);
		UUID storyId = this.submissions.submit(authorRef, draftId, Visibility.UNLISTED).storyId();
		this.stories.add(storyId);
		suspend(storyId);

		var revision = this.submissions.submit(authorRef, draftId, Visibility.PUBLIC);
		this.queue.decide(REVIEWER_REF, revision.storyId(), ReviewVerdict.REJECT, List.of(), null);

		assertThat(column(revision.storyId(), "review_status")).isEqualTo("rejected");
		assertThat(column(revision.storyId(), "visibility")).isEqualTo("private");
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

	/**
	 * <b>수동 정지는 {@code review_status} 만 바꾼다</b> (§13-64, §13-41).
	 *
	 * <p>가시성을 지우면 사람이 나중에 통과시킬 때 <b>어디로 돌려놓아야 하는지</b>를 알 수
	 * 없다. 자동 정지가 이미 그렇게 하고 있고, 수동 정지도 <b>같은 자리</b>를 쓴다 — 두 경로가
	 * 같은 일을 다르게 하면 되돌리는 규칙도 둘이 된다.
	 */
	@Test
	void S13_64_a_manual_suspension_changes_only_the_review_status() {
		UUID storyId = submit(Visibility.PUBLIC);
		this.queue.decide(REVIEWER_REF, storyId, ReviewVerdict.PASS, List.of(), null);

		var decision = this.queue.decide(REVIEWER_REF, storyId, ReviewVerdict.SUSPEND,
				List.of(SafetyCategory.RATING_EXCEEDED), "사후 확인에서 걸렸다");

		assertThat(decision.reviewStatus()).isEqualTo(ReviewStatus.SUSPENDED);
		assertThat(column(storyId, "review_status")).isEqualTo("suspended");
		assertThat(column(storyId, "visibility")).isEqualTo("public");
	}

	/**
	 * <b>내려간 작품은 타인에게 보이지 않는다</b> (I-8, R2.3).
	 *
	 * <p>가시성을 남겨 두는 것이 <b>노출을 남겨 두는 것</b>을 뜻하지 않는다 — 조회 조건이
	 * {@code approved} <b>AND</b> {@code visibility <> private} 이므로 {@code suspended}
	 * 하나로 이미 닫힌다.
	 */
	@Test
	void I8_a_manually_suspended_story_is_no_longer_visible_to_others() {
		UUID storyId = submit(Visibility.PUBLIC);
		this.queue.decide(REVIEWER_REF, storyId, ReviewVerdict.PASS, List.of(), null);
		assertThat(this.stories(storyId)).isPresent();

		this.queue.decide(REVIEWER_REF, storyId, ReviewVerdict.SUSPEND, List.of(), null);

		assertThat(this.stories(storyId)).isEmpty();
	}

	/**
	 * <b>통과가 있던 자리로 되돌린다</b> (§13-41, §13-64).
	 *
	 * <p>{@code unlisted} 였던 작품이 복귀하면서 {@code public} 이 되면 그것은 복귀가 아니다 —
	 * 정지 하나로 작품이 <b>더 넓게</b> 열리는 길이 생긴다.
	 */
	@Test
	void S13_41_passing_a_manually_suspended_story_returns_it_to_where_it_was() {
		UUID storyId = submit(Visibility.UNLISTED);
		this.queue.decide(REVIEWER_REF, storyId, ReviewVerdict.SUSPEND, List.of(), null);

		var decision = this.queue.decide(REVIEWER_REF, storyId, ReviewVerdict.PASS, List.of(), null);

		assertThat(decision.reviewStatus()).isEqualTo(ReviewStatus.APPROVED);
		assertThat(column(storyId, "review_status")).isEqualTo("approved");
		assertThat(column(storyId, "visibility")).isEqualTo("unlisted");
	}

	/**
	 * <b>이미 내려간 것을 또 내리지 않는다</b> (§13-64) — 멱등이 아니라 409 다.
	 *
	 * <p>이력은 append-only 이므로 (I-5) 멱등하게 받으면 정지 기록이 두 줄 남고, 그것은
	 * <b>두 번의 사건</b>으로 읽힌다. 이미 큐에 있는 작품에 남은 일은 {@code pass} 또는
	 * {@code reject} 다.
	 */
	@Test
	void S13_64_suspending_an_already_suspended_story_is_a_conflict() {
		UUID storyId = submit(Visibility.UNLISTED);
		this.queue.decide(REVIEWER_REF, storyId, ReviewVerdict.SUSPEND, List.of(), null);

		assertThatThrownBy(
				() -> this.queue.decide(REVIEWER_REF, storyId, ReviewVerdict.SUSPEND, List.of(), null))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.REVIEW_NOT_PENDING);
		assertThat(this.reviews.findByStoryIdOrderByReviewedAtDesc(storyId))
				.filteredOn(review -> review.getVerdict() == ReviewVerdict.SUSPEND).hasSize(1);
	}

	/**
	 * <b>큐에서 기다리는 작품은 정지 대상이 아니다</b> (§13-64).
	 *
	 * <p>아직 아무에게도 보인 적이 없어 내릴 것이 없고, 정지시키면 <b>"어디서 왔는가"라는
	 * 표식을 잃는다</b> (§13-48) — 통과가 {@code public} 을 열어야 할 작품이 있던 자리로
	 * 돌아가게 된다. 열지 않기로 하는 판정은 {@code reject} 다.
	 */
	@Test
	void S13_64_a_story_still_waiting_in_the_queue_cannot_be_suspended() {
		UUID storyId = submit(Visibility.PUBLIC);

		assertThatThrownBy(
				() -> this.queue.decide(REVIEWER_REF, storyId, ReviewVerdict.SUSPEND, List.of(), null))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.REVIEW_NOT_PENDING);
		assertThat(column(storyId, "review_status")).isEqualTo("in_review");
	}

	/**
	 * <b>왜 내려갔는지가 남는다</b> (R8.9, R8.7).
	 *
	 * <p>사유는 <b>카테고리만</b>이다 — 검수자가 적은 {@code note} 는 작성자에게 가지 않는다.
	 * 그리고 <b>누가 내렸는지 모르는 정지는 감사에 쓸모가 없다.</b>
	 */
	@Test
	void R8_9_a_manual_suspension_is_recorded_with_its_reviewer_and_category() {
		UUID storyId = submit(Visibility.UNLISTED);

		this.queue.decide(REVIEWER_REF, storyId, ReviewVerdict.SUSPEND,
				List.of(SafetyCategory.RATING_EXCEEDED), "이나린 이라는 이름이 걸렸다");

		assertThat(this.reviews.findFirstByStoryIdOrderByReviewedAtDesc(storyId)).get()
				.satisfies(review -> {
					assertThat(review.getVerdict()).isEqualTo(ReviewVerdict.SUSPEND);
					assertThat(review.getStage()).isEqualTo(ReviewStage.HUMAN);
					assertThat(review.getReviewerRef()).isEqualTo(REVIEWER_REF);
					assertThat(review.getReasons()).contains("rating_exceeded").doesNotContain("이나린");
				});
	}

	/** <b>내려간 작품은 큐에 남는다</b> (§13-41) — 이어지는 판정을 그대로 받는다. */
	@Test
	void S13_64_a_manually_suspended_story_stays_in_the_queue() {
		UUID storyId = submit(Visibility.UNLISTED);

		this.queue.decide(REVIEWER_REF, storyId, ReviewVerdict.SUSPEND, List.of(), null);

		assertThat(this.queue.pending()).extracting(ReviewQueueService.QueueItem::storyId)
				.contains(storyId);
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

	/** 타인이 보는 문. 여기서 비면 <b>아무도 그 작품에 닿지 못한다</b> (I-8, R2.3). */
	private java.util.Optional<com.neowadaeum.catalog.query.StoryDetailView> stories(UUID storyId) {
		return this.catalogQueries.detail(storyId);
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

	/** 신고 누적으로 내려간 상태를 만든다 (B-57). 경로가 아니라 상태가 필요한 테스트다. */
	private void suspend(UUID storyId) {
		JdbcClient.create(this.catalog)
				.sql("UPDATE story SET review_status = 'suspended' WHERE id = ?")
				.param(storyId).update();
	}

	private String column(UUID storyId, String name) {
		return JdbcClient.create(this.catalog).sql("SELECT " + name + "::text FROM story WHERE id = ?")
				.param(storyId).query(String.class).optional().orElse(null);
	}
}

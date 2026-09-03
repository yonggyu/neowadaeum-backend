package com.neowadaeum.authoring.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.authoring.draft.DraftService;
import com.neowadaeum.authoring.draft.StoryDraftRepository;
import com.neowadaeum.catalog.query.MyStoryView;
import com.neowadaeum.catalog.query.StoryCatalogFacade;
import com.neowadaeum.common.spi.SafetyCategory;
import com.neowadaeum.common.spi.StoryReviewTimes;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * §13-57 (#290) — <b>신청 시각과 승인 시각이 실제 검수 경로에서 그렇게 쌓이는가.</b>
 *
 * <p>회차를 가르는 규칙 자체는 {@link StoryReviewTimelineTests} 가 본다. 여기서 확인하는 것은
 * <b>서비스가 그 규칙이 기대하는 기록을 남기는가</b>다 — 규칙이 맞아도 제출이 이력을 남기지
 * 않으면 화면은 여전히 날짜를 갖지 못한다.
 *
 * <p><b>{@code getDraftReview} 와 {@code getMyStories} 를 함께 본다</b> (§13.7, §13.8). 두
 * 화면이 다른 날짜를 말하면 그것은 계약이 거짓말을 시작하는 지점이다.
 *
 * <p><b>S-11 — 픽스처는 전부 가상의 문자열이다.</b>
 */
class ReviewTimestampIntegrationTests extends ContainerTestBase {

	private static final String PAYLOAD = """
			{"title":"봄의 학교","shortDesc":"짧은 소개","worldIntro":"소개",
			 "worldPrompt":"봄의 학교에서 시작한다.",
			 "chapters":[{"title":"1장","summarySeed":"시작"}],
			 "endings":[{"label":"좋은 끝","epilogueText":"잘 끝났다."}]}
			""";

	private static final UUID REVIEWER_REF = UUID.fromString("00000000-0000-4000-8000-0000000000e2");

	@Autowired
	private SubmissionService submissions;

	@Autowired
	private ReviewQueueService queue;

	@Autowired
	private DraftService drafts;

	@Autowired
	private StoryDraftRepository draftRows;

	@Autowired
	private StoryReviewRepository reviews;

	@Autowired
	private StoryCatalogFacade catalogQueries;

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

	/**
	 * <b>아직 내지 않은 원고에는 두 시각이 모두 없다.</b>
	 *
	 * <p>지어낸 시각을 넣으면 화면은 <b>신청하지 않은 원고를 기다리는 중</b>이라고 적는다.
	 */
	@Test
	void S13_57_a_draft_that_was_never_submitted_has_neither_time() {
		UUID draftId = givenDraft();

		var status = this.submissions.reviewStatus(authorOf(draftId), draftId);

		assertThat(status.reviewStatus()).isEqualTo(ReviewStatus.DRAFT);
		assertThat(status.times()).isEqualTo(StoryReviewTimes.NONE);
	}

	/**
	 * 제출 직후 — <b>신청은 있고 승인은 없다</b> (§13.8 의 "검수 대기").
	 *
	 * <p>"보통 1~3일"이라는 안내가 뜻을 가지려면 <b>언제부터 세는지</b>가 있어야 한다.
	 */
	@Test
	void S13_57_a_public_submission_has_a_requested_time_and_no_verdict_yet() {
		UUID draftId = givenDraft();
		UUID authorRef = authorOf(draftId);

		var submitted = this.submissions.submit(authorRef, draftId, Visibility.PUBLIC);
		this.stories.add(submitted.storyId());

		assertThat(submitted.reviewStatus()).isEqualTo(ReviewStatus.IN_REVIEW);
		assertThat(submitted.times().submittedAt()).isNotNull();
		assertThat(submitted.times().reviewedAt()).isNull();
	}

	/**
	 * <b>{@code unlisted} 는 사람을 거치지 않으므로 승인 시각이 없다</b> (R8.6).
	 *
	 * <p>자동 통과 시각을 승인 시각이라고 적으면 화면은 <b>사람이 본 작품과 보지 않은 작품을
	 * 구분하지 못한다.</b>
	 */
	@Test
	void S13_57_an_automatically_approved_story_has_no_human_verdict_time() {
		UUID draftId = givenDraft();

		var submitted = this.submissions.submit(authorOf(draftId), draftId, Visibility.UNLISTED);
		this.stories.add(submitted.storyId());

		assertThat(submitted.reviewStatus()).isEqualTo(ReviewStatus.APPROVED);
		assertThat(submitted.times().submittedAt()).isNotNull();
		assertThat(submitted.times().reviewedAt()).isNull();
	}

	/**
	 * 승인 후 — <b>둘 다 있고 승인이 신청보다 뒤다</b> (§13-48 — 큐가 쓰는 마지막 판정 시각과
	 * 같은 기록이다).
	 */
	@Test
	void S13_48_a_human_pass_fills_the_reviewed_time_after_the_requested_one() {
		UUID draftId = givenDraft();
		UUID authorRef = authorOf(draftId);
		var submitted = this.submissions.submit(authorRef, draftId, Visibility.PUBLIC);
		this.stories.add(submitted.storyId());

		this.queue.decide(REVIEWER_REF, submitted.storyId(), ReviewVerdict.PASS, List.of(), null);

		var seen = this.submissions.reviewStatus(authorRef, draftId);
		assertThat(seen.reviewStatus()).isEqualTo(ReviewStatus.APPROVED);
		assertThat(seen.times().submittedAt())
				.isEqualTo(submitted.times().submittedAt());
		assertThat(seen.times().reviewedAt()).isNotNull()
				.isAfterOrEqualTo(seen.times().submittedAt());
	}

	/**
	 * 재제출 후 — <b>신청 시각은 새것이고 승인 시각은 다시 비어 있다</b> (§13-57).
	 *
	 * <p>지난 회차의 승인을 남겨 두면 화면은 <b>"검수 대기"라고 적으면서 승인 날짜를 함께</b>
	 * 보여 준다. 아직 답이 없다는 것이 사실이다.
	 */
	@Test
	void S13_57_a_resubmission_starts_a_new_round() {
		UUID draftId = givenDraft();
		UUID authorRef = authorOf(draftId);
		var first = this.submissions.submit(authorRef, draftId, Visibility.PUBLIC);
		this.stories.add(first.storyId());
		this.queue.decide(REVIEWER_REF, first.storyId(), ReviewVerdict.PASS, List.of(), null);
		var approved = this.submissions.reviewStatus(authorRef, draftId);

		var revision = this.submissions.submit(authorRef, draftId, Visibility.PUBLIC);

		assertThat(revision.reviewStatus()).isEqualTo(ReviewStatus.IN_REVIEW);
		assertThat(revision.times().submittedAt()).isAfter(approved.times().submittedAt());
		assertThat(revision.times().reviewedAt()).isNull();
	}

	/**
	 * <b>목록도 같은 날짜를 말한다</b> (§13.7 의 {@code MyStoryItem}).
	 *
	 * <p>규칙이 두 곳에 있으면 목록과 상세가 다른 날을 적고, 그때 어느 쪽이 맞는지는 아무도
	 * 답할 수 없다.
	 */
	@Test
	void S13_57_my_stories_carries_the_same_two_times() {
		UUID draftId = givenDraft();
		UUID authorRef = authorOf(draftId);
		var submitted = this.submissions.submit(authorRef, draftId, Visibility.PUBLIC);
		this.stories.add(submitted.storyId());
		this.queue.decide(REVIEWER_REF, submitted.storyId(), ReviewVerdict.REJECT,
				List.of(SafetyCategory.RATING_EXCEEDED), null);
		var seen = this.submissions.reviewStatus(authorRef, draftId);

		List<MyStoryView> mine = this.catalogQueries.mine(authorRef, null, null).stories();

		assertThat(mine).singleElement().satisfies(story -> {
			assertThat(story.submittedAt()).isEqualTo(seen.times().submittedAt());
			assertThat(story.reviewedAt()).isEqualTo(seen.times().reviewedAt());
			assertThat(story.reviewedAt()).isNotNull();
		});
	}

	/**
	 * <b>미리보기로만 만들어진 작품은 두 시각이 모두 없다</b> (§13-5).
	 *
	 * <p>목록에는 뜨지만 검수를 요청한 적이 없다 — 없는 것과 아직 하지 않은 것은 다르다.
	 */
	@Test
	void S13_57_a_story_with_no_review_history_shows_neither_time_in_the_list() {
		UUID authorRef = UUID.randomUUID();
		UUID storyId = UUID.randomUUID();
		insertDraftStory(storyId, authorRef);

		List<MyStoryView> mine = this.catalogQueries.mine(authorRef, null, null).stories();

		assertThat(mine).singleElement().satisfies(story -> {
			assertThat(story.storyId()).isEqualTo(storyId);
			assertThat(story.submittedAt()).isNull();
			assertThat(story.reviewedAt()).isNull();
		});
	}

	/** 검수 이력 없이 존재하는 작품. 미리보기가 만드는 자리와 같다 (§13-5). */
	private void insertDraftStory(UUID storyId, UUID authorRef) {
		JdbcClient.create(this.catalog).sql("""
				INSERT INTO story (id, slug, title, author_type, author_ref, visibility, review_status,
				                   created_at)
				VALUES (?, ?, '봄의 학교', 'user', ?, 'private', 'draft', NOW())
				""").params(storyId, "s-" + storyId, authorRef).update();
		this.stories.add(storyId);
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

}

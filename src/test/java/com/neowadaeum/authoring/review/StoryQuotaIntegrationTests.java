package com.neowadaeum.authoring.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.authoring.UgcLimitProperties;
import com.neowadaeum.authoring.draft.DraftService;
import com.neowadaeum.authoring.draft.StoryDraftRepository;
import com.neowadaeum.catalog.publish.StoryPublisher;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * B-60 — <b>한 계정이 플랫폼 비용을 정하지 않는다</b> (R8.12).
 *
 * <p>작품 하나는 발행된 버전과 챕터·엔딩을 갖고, 검수와 사후 관리(B-59)의 대상이 된다 —
 * 만드는 쪽에 상한이 없으면 <b>그 뒤의 모든 비용에도 상한이 없다.</b>
 */
class StoryQuotaIntegrationTests extends ContainerTestBase {

	private static final String PAYLOAD = """
			{"title":"봄의 학교","shortDescription":"짧은 소개","worldIntro":"소개",
			 "settingDetail":"봄의 학교에서 시작한다.",
			 "chapters":[{"title":"1장","summarySeed":"시작"}],
			 "endings":[{"label":"좋은 끝","epilogueText":"잘 끝났다."}]}
			""";

	@Autowired
	private SubmissionService submissions;

	@Autowired
	private UgcLimitProperties limits;

	@Autowired
	private StoryPublisher publisher;

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

	/**
	 * <b>상한에 닿으면 새 작품을 낼 수 없다</b> (R8.12).
	 *
	 * <p>상한만큼 이미 낸 작성자를 만들고 한 번 더 낸다.
	 */
	@Test
	void R8_12_an_author_at_the_limit_cannot_submit_a_new_story() {
		UUID authorRef = UUID.randomUUID();
		givenSubmittedStories(authorRef, this.limits.storiesPerAuthor());
		UUID draftId = givenDraft(authorRef);

		assertThatThrownBy(() -> this.submissions.submit(authorRef, draftId, Visibility.UNLISTED))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.STORY_LIMIT_REACHED);
	}

	/** 상한 아래에서는 낼 수 있다 — 막는 것만 확인하면 <b>영구히 막는 구현</b>도 통과한다. */
	@Test
	void R8_12_an_author_below_the_limit_can_submit() {
		UUID authorRef = UUID.randomUUID();
		givenSubmittedStories(authorRef, this.limits.storiesPerAuthor() - 1);
		UUID draftId = givenDraft(authorRef);

		var outcome = this.submissions.submit(authorRef, draftId, Visibility.UNLISTED);

		this.stories.add(outcome.storyId());
		assertThat(outcome.reviewStatus()).isEqualTo(ReviewStatus.APPROVED);
	}

	/**
	 * <b>재제출은 개수를 늘리지 않는다</b> (B-56).
	 *
	 * <p>여기서 막으면 상한에 닿은 작성자가 <b>이미 낸 작품조차 고치지 못한다.</b>
	 */
	@Test
	void R8_12_a_resubmission_is_not_blocked_by_the_limit() {
		UUID authorRef = UUID.randomUUID();
		UUID draftId = givenDraft(authorRef);
		UUID storyId = this.submissions.submit(authorRef, draftId, Visibility.UNLISTED).storyId();
		this.stories.add(storyId);
		givenSubmittedStories(authorRef, this.limits.storiesPerAuthor());

		var again = this.submissions.submit(authorRef, draftId, Visibility.UNLISTED);

		assertThat(again.storyId()).isEqualTo(storyId);
	}

	/**
	 * <b>미리보기가 만든 작품은 세지 않는다</b> (§13-37).
	 *
	 * <p>그것은 매 미리보기마다 늘어나고 파기는 B-61 이 가져간다 — 함께 세면 <b>미리보기 몇
	 * 번으로 작품을 못 만들게 된다.</b>
	 */
	@Test
	void R8_12_preview_stories_do_not_count_toward_the_limit() {
		UUID authorRef = UUID.randomUUID();
		givenPreviewStories(authorRef, this.limits.storiesPerAuthor());
		UUID draftId = givenDraft(authorRef);

		var outcome = this.submissions.submit(authorRef, draftId, Visibility.UNLISTED);

		this.stories.add(outcome.storyId());
		assertThat(outcome.reviewStatus()).isEqualTo(ReviewStatus.APPROVED);
	}

	/** 상한은 작성자별이다 — 남이 많이 냈다고 내가 막히지 않는다. */
	@Test
	void R8_12_the_limit_is_per_author() {
		UUID crowded = UUID.randomUUID();
		givenSubmittedStories(crowded, this.limits.storiesPerAuthor());
		UUID newcomer = UUID.randomUUID();
		UUID draftId = givenDraft(newcomer);

		var outcome = this.submissions.submit(newcomer, draftId, Visibility.UNLISTED);

		this.stories.add(outcome.storyId());
		assertThat(outcome.reviewStatus()).isEqualTo(ReviewStatus.APPROVED);
	}

	/** 제출을 지난 작품만 센다 — 세는 규칙 자체를 고정한다. */
	@Test
	void R8_12_only_submitted_stories_are_counted() {
		UUID authorRef = UUID.randomUUID();
		givenPreviewStories(authorRef, 2);
		givenSubmittedStories(authorRef, 3);

		assertThat(this.publisher.countSubmittedStoriesOf(authorRef)).isEqualTo(3);
	}

	/**
	 * 이미 낸 작품들.
	 *
	 * <p>제출 경로로 만들면 상한 자체에 걸리므로 <b>발행 경로로 직접</b> 만든다 — 확인하려는
	 * 것은 세는 규칙이지 만드는 경로가 아니다.
	 */
	private void givenSubmittedStories(UUID authorRef, int count) {
		for (int i = 0; i < count; i++) {
			this.stories.add(givenStory(authorRef, "approved"));
		}
	}

	/** 미리보기가 남기는 것 — {@code draft} 이며 아무에게도 보이지 않는다 (§13-37). */
	private void givenPreviewStories(UUID authorRef, int count) {
		for (int i = 0; i < count; i++) {
			this.stories.add(givenStory(authorRef, "draft"));
		}
	}

	private UUID givenStory(UUID authorRef, String reviewStatus) {
		UUID storyId = UUID.randomUUID();
		JdbcClient.create(this.catalog).sql("""
						INSERT INTO story (id, slug, title, short_desc, author_type, author_ref,
								visibility, review_status, created_at)
						VALUES (?, ?, ?, ?, 'user', ?, 'unlisted', ?, NOW())
						""")
				.params(storyId, "quota-" + storyId, "봄의 학교", "소개", authorRef, reviewStatus)
				.update();
		return storyId;
	}

	private UUID givenDraft(UUID authorRef) {
		UUID draftId = this.drafts.create(authorRef).getId();
		this.drafts.save(authorRef, draftId, 5, PAYLOAD);
		return draftId;
	}
}

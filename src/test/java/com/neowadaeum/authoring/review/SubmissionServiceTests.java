package com.neowadaeum.authoring.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.authoring.blocklist.BlocklistAdminService;
import com.neowadaeum.authoring.blocklist.BlocklistEntryRepository;
import com.neowadaeum.authoring.blocklist.BlocklistKind;
import com.neowadaeum.authoring.blocklist.BlocklistSeverity;
import com.neowadaeum.authoring.blocklist.BlocklistTeardown;
import com.neowadaeum.authoring.blocklist.PersistentBlocklistQuery;
import com.neowadaeum.authoring.draft.DraftService;
import com.neowadaeum.authoring.draft.StoryDraftRepository;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * B-54 — <b>승인이 곧 게시다</b> (R8.8), 그리고 <b>{@code public} 은 사람을 기다린다</b> (R8.6).
 *
 * <p><b>S-11 — 픽스처는 전부 가상의 문자열이다.</b>
 */
class SubmissionServiceTests extends ContainerTestBase {

	private static final String PAYLOAD = """
			{"title":"봄의 학교","shortDescription":"짧은 소개","worldIntro":"소개",
			 "settingDetail":"봄의 학교에서 시작한다.",
			 "chapters":[{"title":"1장","summarySeed":"시작"}],
			 "endings":[{"label":"좋은 끝","epilogueText":"잘 끝났다."}]}
			""";

	@Autowired
	private SubmissionService submissions;

	@Autowired
	private DraftService drafts;

	@Autowired
	private StoryDraftRepository draftRows;

	@Autowired
	private StoryReviewRepository reviews;

	@Autowired
	private BlocklistAdminService blocklist;

	@Autowired
	private BlocklistEntryRepository blocklistRows;

	@Autowired
	private PersistentBlocklistQuery blocklistCache;

	@Autowired
	@Qualifier("catalogDataSource")
	private DataSource catalog;

	private final java.util.List<UUID> stories = new java.util.ArrayList<>();

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
		BlocklistTeardown.clear(this.blocklistRows, this.blocklistCache);
	}

	/** {@code unlisted} 는 자동 검수만으로 승인되고 <b>곧바로 게시된다</b> (R8.6, R8.8). */
	@Test
	void R8_6_an_unlisted_story_is_approved_by_the_automatic_review() {
		var outcome = submit(Visibility.UNLISTED);

		assertThat(outcome.reviewStatus()).isEqualTo(ReviewStatus.APPROVED);
		assertThat(outcome.visibility()).isEqualTo(Visibility.UNLISTED);
		assertThat(column(outcome.storyId(), "current_version_id")).isNotNull();
	}

	/**
	 * <b>{@code public} 은 자동 검수만으로 승인되지 않는다</b> (R8.6).
	 *
	 * <p>그동안 작품은 {@code private} 이다 — <b>검수 중인 작품이 보이면 검수의 의미가 없다.</b>
	 */
	@Test
	void R8_6_a_public_story_waits_for_a_human() {
		var outcome = submit(Visibility.PUBLIC);

		assertThat(outcome.reviewStatus()).isEqualTo(ReviewStatus.IN_REVIEW);
		assertThat(outcome.visibility()).isEqualTo(Visibility.PRIVATE);
		assertThat(column(outcome.storyId(), "current_version_id")).isNull();
	}

	/** 자동 검수 이력이 남는다 — <b>왜 그렇게 됐는지</b>는 거기에 있다. */
	@Test
	void R8_7_the_automatic_review_is_recorded() {
		var outcome = submit(Visibility.UNLISTED);

		assertThat(this.reviews.findByStoryIdOrderByReviewedAtDesc(outcome.storyId()))
				.singleElement().satisfies(review -> {
					assertThat(review.getStage()).isEqualTo(ReviewStage.AUTO);
					assertThat(review.getVerdict()).isEqualTo(ReviewVerdict.PASS);
					assertThat(review.getReviewerRef()).isNull();
				});
	}

	/**
	 * <b>반려 사유는 카테고리만이다</b> (R8.7, S-11).
	 *
	 * <p>어떤 항목에 걸렸는지를 알려 주면 우회 학습을 돕는다.
	 */
	@Test
	void R8_7_a_rejection_names_categories_but_not_entries() {
		String fictional = "이나린";
		this.blocklist.register(BlocklistKind.REAL_PERSON, fictional, BlocklistSeverity.BLOCK, "test");
		UUID draftId = givenDraft(PAYLOAD.replace("봄의 학교에서 시작한다.", fictional + " 이 나온다."));

		var outcome = this.submissions.submit(authorOf(draftId), draftId, Visibility.UNLISTED);

		assertThat(outcome.reviewStatus()).isEqualTo(ReviewStatus.REJECTED);
		assertThat(outcome.rejectReasons()).containsExactly("real_person_harm");
		assertThat(outcome.rejectReasons()).allSatisfy(
				reason -> assertThat(reason).doesNotContain(fictional));
	}

	/** <b>반려된 원고는 작품을 만들지 않는다.</b> 아무도 볼 수 없더라도 그것은 쌓인다. */
	@Test
	void R8_7_a_rejected_submission_publishes_nothing() {
		this.blocklist.register(BlocklistKind.REAL_PERSON, "이나린", BlocklistSeverity.BLOCK, "test");
		UUID draftId = givenDraft(PAYLOAD.replace("봄의 학교에서 시작한다.", "이나린 이 나온다."));

		var outcome = this.submissions.submit(authorOf(draftId), draftId, Visibility.UNLISTED);

		assertThat(outcome.storyId()).isNull();
		assertThat(this.reviews.findAll()).isEmpty();
	}

	/** <b>챕터와 엔딩도 검수 대상이다</b> (R8.5) — 세계관만 보면 제목에 넣으면 통과한다. */
	@Test
	void R8_5_chapters_and_endings_are_screened_too() {
		this.blocklist.register(BlocklistKind.REAL_PERSON, "이나린", BlocklistSeverity.BLOCK, "test");
		UUID draftId = givenDraft(PAYLOAD.replace("\"label\":\"좋은 끝\"", "\"label\":\"이나린 의 끝\""));

		var outcome = this.submissions.submit(authorOf(draftId), draftId, Visibility.UNLISTED);

		assertThat(outcome.reviewStatus()).isEqualTo(ReviewStatus.REJECTED);
	}

	/**
	 * <b>인물도 검수 대상이다</b> (R8.5, §13-75).
	 *
	 * <p>{@code persona} 는 매 턴 모델에게 들어가고 (인물 레이어), 이름과 한 줄 소개는 타인의
	 * 상세 화면에 뜬다 (I-8). #350 이 인물을 발행하기 시작한 뒤로 <b>검수만 그것을 보지
	 * 않았다.</b>
	 */
	@Test
	void R8_5_characters_are_screened_too() {
		this.blocklist.register(BlocklistKind.REAL_PERSON, "이나린", BlocklistSeverity.BLOCK, "test");
		UUID draftId = givenDraft(PAYLOAD.replace("\"chapters\":[",
				"\"characters\":[{\"name\":\"연우\",\"persona\":\"이나린 을 닮았다.\"}],\"chapters\":["));

		var outcome = this.submissions.submit(authorOf(draftId), draftId, Visibility.UNLISTED);

		assertThat(outcome.reviewStatus()).isEqualTo(ReviewStatus.REJECTED);
		assertThat(outcome.storyId()).isNull();
	}

	/**
	 * <b>플래그 이름도 검수 대상이다</b> (R8.5, §13-75).
	 *
	 * <p>선언된 이름은 화이트리스트로 발행되고 그 뒤 매 턴 {@code GAME_STATE} 로 나간다 —
	 * <b>짧다는 이유로 다르게 보지 않는다.</b>
	 */
	@Test
	void R8_5_declared_flags_are_screened_too() {
		this.blocklist.register(BlocklistKind.REAL_PERSON, "이나린", BlocklistSeverity.BLOCK, "test");
		UUID draftId = givenDraft(
				PAYLOAD.replace("\"chapters\":[", "\"flags\":[\"이나린_만남\"],\"chapters\":["));

		var outcome = this.submissions.submit(authorOf(draftId), draftId, Visibility.UNLISTED);

		assertThat(outcome.reviewStatus()).isEqualTo(ReviewStatus.REJECTED);
		assertThat(outcome.storyId()).isNull();
	}

	private SubmissionService.SubmissionOutcome submit(Visibility visibility) {
		UUID draftId = givenDraft(PAYLOAD);
		var outcome = this.submissions.submit(authorOf(draftId), draftId, visibility);
		if (outcome.storyId() != null) {
			this.stories.add(outcome.storyId());
		}
		return outcome;
	}

	private UUID givenDraft(String payload) {
		UUID authorRef = UUID.randomUUID();
		UUID draftId = this.drafts.create(authorRef).getId();
		this.drafts.save(authorRef, draftId, 5, payload);
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

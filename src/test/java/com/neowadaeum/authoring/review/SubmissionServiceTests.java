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
			// 인물은 버전을 FK 로 참조한다 (#350). 여기서 빠지면 아래 story_version 삭제가
			// 예외를 내고, 그 예외가 뒷정리의 나머지를 건너뛰어 **실패가 다음 테스트로 옮겨
			// 붙는다** — 그때 터지는 것은 이 테스트가 아니다.
			jdbc.sql("DELETE FROM character WHERE story_id = ?").param(storyId).update();
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

	/**
	 * <b>커버가 있는 원고는 자동 승인되지 않는다</b> (§13-83, #391).
	 *
	 * <p>L0 · L1 · L3 는 전부 문자열을 본다 — <b>이미지는 그 어느 것도 지나지 않는다.</b>
	 * 커버는 15세 등급 판정의 대상이므로 (R8.5) 판정 주체가 사람뿐이면 사람이 볼 때까지
	 * 게시하지 않는 것이 그 사실과 맞는 처리다.
	 *
	 * <p><b>객체 키는 가상의 문자열이다</b> (S-11). 여기서 확인하는 것은 이미지의 내용이 아니라
	 * <b>이미지가 있다는 사실이 길을 가르는가</b>다.
	 */
	@Test
	void S13_83_a_cover_image_sends_the_submission_to_a_human() {
		var outcome = submit(Visibility.UNLISTED,
				PAYLOAD.replace("\"shortDescription\":",
						"\"coverImage\":\"drafts/x/cover\",\"shortDescription\":"));

		assertThat(outcome.reviewStatus()).isEqualTo(ReviewStatus.IN_REVIEW);
		assertThat(column(outcome.storyId(), "current_version_id")).isNull();
	}

	/** <b>초상도 같다</b> (§13-83) — 인물 수만큼 있으므로 커버보다 오히려 많다. */
	@Test
	void S13_83_a_character_portrait_sends_the_submission_to_a_human() {
		var outcome = submit(Visibility.UNLISTED, PAYLOAD.replace("\"chapters\":[",
				"\"characters\":[{\"name\":\"연우\",\"portraitImage\":\"drafts/x/p\"}],\"chapters\":["));

		assertThat(outcome.reviewStatus()).isEqualTo(ReviewStatus.IN_REVIEW);
		assertThat(column(outcome.storyId(), "current_version_id")).isNull();
	}

	/**
	 * <b>{@code private} 도 예외가 아니다</b> (§13-83).
	 *
	 * <p>{@code private} 만 자동으로 열어 두면 그 작품을 {@code unlisted} 로 넓히는 길이 사람을
	 * 지나지 않는다 — 넓히는 방향은 승격이 아니어서 재검수를 열지 않기 때문이다. 예외가 곧
	 * 세탁 경로가 된다.
	 */
	@Test
	void S13_83_even_a_private_submission_with_an_image_waits_for_a_human() {
		var outcome = submit(Visibility.PRIVATE,
				PAYLOAD.replace("\"shortDescription\":",
						"\"coverImage\":\"drafts/x/cover\",\"shortDescription\":"));

		assertThat(outcome.reviewStatus()).isEqualTo(ReviewStatus.IN_REVIEW);
	}

	/**
	 * <b>통과가 열 자리를 작품이 들고 간다</b> (§13-83).
	 *
	 * <p>지금까지 큐에 오는 길은 {@code public} 제출 하나여서 통과가 여는 값을 물어볼 필요가
	 * 없었다. 이미지가 그 전제를 깼으므로 <b>작성자가 요청한 값</b>이 함께 적힌다 — 없으면
	 * 통과가 작성자가 고르지 않은 넓이로 작품을 연다 (I-8).
	 */
	@Test
	void S13_83_the_requested_visibility_is_recorded_for_the_human() {
		var outcome = submit(Visibility.UNLISTED,
				PAYLOAD.replace("\"shortDescription\":",
						"\"coverImage\":\"drafts/x/cover\",\"shortDescription\":"));

		assertThat(column(outcome.storyId(), "pending_visibility")).isEqualTo("unlisted");
		// 돌아갈 자리는 그것과 다른 사실이다 — 처음 내는 작품은 아무에게도 보인 적이 없다.
		assertThat(column(outcome.storyId(), "visibility")).isEqualTo("private");
	}

	/** <b>이미지가 없는 원고는 지금까지와 같다</b> (R8.6) — 요청도 적히지 않는다. */
	@Test
	void S13_83_a_manuscript_without_an_image_records_no_request() {
		var outcome = submit(Visibility.UNLISTED);

		assertThat(column(outcome.storyId(), "pending_visibility")).isNull();
	}

	private SubmissionService.SubmissionOutcome submit(Visibility visibility) {
		return submit(visibility, PAYLOAD);
	}

	private SubmissionService.SubmissionOutcome submit(Visibility visibility, String payload) {
		UUID draftId = givenDraft(payload);
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

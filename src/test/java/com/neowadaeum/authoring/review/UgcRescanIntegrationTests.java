package com.neowadaeum.authoring.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.authoring.blocklist.BlocklistAdminService;
import com.neowadaeum.authoring.blocklist.BlocklistKind;
import com.neowadaeum.authoring.blocklist.BlocklistSeverity;
import com.neowadaeum.authoring.draft.DraftService;
import com.neowadaeum.authoring.draft.StoryDraftRepository;
import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.repository.PlaySessionRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * B-59(1/2) — <b>승인은 끝이 아니다</b> (R9.4).
 *
 * <p>블록리스트는 운영 중에 늘어난다. 갱신이 앞으로 만들어질 것에만 적용되면 <b>이미 게시된
 * 것은 영원히 옛 기준</b>으로 남는다.
 *
 * <p><b>S-11 — 픽스처는 전부 가상의 문자열이다.</b>
 */
class UgcRescanIntegrationTests extends ContainerTestBase {

	/**
	 * 가상의 이름. 실제 블록리스트 항목이 아니다 (S-11).
	 *
	 * <p><b>이 클래스만 쓰는 이름이다.</b> 블록리스트 조회에는 수명 1분의 스냅샷이 있고
	 * (§13-31), 다른 테스트 클래스가 Repository 로 지우면 그 스냅샷이 살아남는다 — 같은 이름을
	 * 쓰면 <b>남의 뒷정리가 이 클래스의 제출을 반려시킨다.</b>
	 */
	private static final String FICTIONAL = "타비린";

	private static final String CLEAN_PAYLOAD = """
			{"title":"봄의 학교","shortDescription":"짧은 소개","worldIntro":"소개",
			 "settingDetail":"봄의 학교에서 시작한다.",
			 "chapters":[{"title":"1장","summarySeed":"시작"}],
			 "endings":[{"label":"좋은 끝","epilogueText":"잘 끝났다."}]}
			""";

	/** 이 클래스만 쓰는 플레이어 — 뒷정리가 남의 세션을 지우지 않는다. */
	private static final UUID PLAYER_REF = UUID.fromString("00000000-0000-4000-8000-0000000000f3");

	@Autowired
	private UgcRescanner rescanner;

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
	private BlocklistAdminService blocklist;

	@Autowired
	private PlaySessionRepository sessions;

	@Autowired
	@Qualifier("catalogDataSource")
	private DataSource catalog;

	private final List<UUID> stories = new java.util.ArrayList<>();

	@AfterEach
	void clear() {
		this.sessions.findAll().stream().filter(session -> PLAYER_REF.equals(session.getPlayerRef()))
				.forEach(this.sessions::delete);
		JdbcClient jdbc = JdbcClient.create(this.catalog);
		this.reviews.deleteAll();
		for (UUID storyId : this.stories) {
			// 인물도 버전에 매달려 있다 (§13-1) — 남겨 두면 버전 삭제가 FK 로 막히고, 실패가
			// **다음 테스트의 제출 반려**로 옮겨 붙는다 (블록리스트 뒷정리까지 못 간다).
			jdbc.sql("DELETE FROM character WHERE story_id = ?").param(storyId).update();
			jdbc.sql("DELETE FROM chapter_def WHERE story_id = ?").param(storyId).update();
			jdbc.sql("DELETE FROM ending_def WHERE story_id = ?").param(storyId).update();
			jdbc.sql("UPDATE story SET current_version_id = NULL WHERE id = ?").param(storyId).update();
			jdbc.sql("DELETE FROM story_version WHERE story_id = ?").param(storyId).update();
			jdbc.sql("DELETE FROM story WHERE id = ?").param(storyId).update();
		}
		this.stories.clear();
		this.draftRows.deleteAll();
		// **서비스를 거쳐 지운다.** Repository 로 지우면 스냅샷 캐시가 무효화되지 않고(§13-31,
		// 수명 1분) 앞 테스트의 항목이 남아 다음 테스트의 제출이 반려된다 — 그러면 작품이
		// 만들어지지 않고 실패가 "재스캔이 안 된다"로 보인다.
		this.blocklist.list().forEach(entry -> this.blocklist.remove(entry.getId()));
	}

	/**
	 * <b>어제 통과한 것이 오늘 걸린다</b> (R9.4).
	 *
	 * <p>제출 시점에는 목록에 없던 항목이므로 통과했다. 목록이 늘어난 뒤 다시 보면 걸린다.
	 */
	@Test
	void R9_4_a_blocklist_update_catches_an_already_approved_story() {
		UUID storyId = givenApprovedStory(CLEAN_PAYLOAD.replace("봄의 학교에서 시작한다.",
				FICTIONAL + " 이 나온다."));
		this.blocklist.register(BlocklistKind.REAL_PERSON, FICTIONAL, BlocklistSeverity.BLOCK, "test");

		int suspended = this.rescanner.rescan();

		assertThat(suspended).isEqualTo(1);
		assertThat(column(storyId, "review_status")).isEqualTo("suspended");
	}

	/** 걸린 작품은 <b>검수 큐에 오른다</b> — 자동으로 내리되 자동으로 올리지 않는다. */
	@Test
	void R9_4_a_rescanned_story_reaches_the_review_queue() {
		UUID storyId = givenApprovedStory(CLEAN_PAYLOAD.replace("좋은 끝", FICTIONAL + " 의 끝"));
		this.blocklist.register(BlocklistKind.REAL_PERSON, FICTIONAL, BlocklistSeverity.BLOCK, "test");

		this.rescanner.rescan();

		assertThat(this.queue.pending()).anySatisfy(item -> {
			assertThat(item.storyId()).isEqualTo(storyId);
			assertThat(item.reviewStatus()).isEqualTo(ReviewStatus.SUSPENDED);
		});
	}

	/**
	 * <b>이력에 카테고리만 남는다</b> (R8.7, S-11).
	 *
	 * <p>어떤 항목에 걸렸는지를 담으면 그 표가 우회 사전이 된다.
	 */
	@Test
	void R8_7_the_rescan_records_categories_but_not_entries() {
		UUID storyId = givenApprovedStory(CLEAN_PAYLOAD.replace("봄의 학교에서 시작한다.",
				FICTIONAL + " 이 나온다."));
		this.blocklist.register(BlocklistKind.REAL_PERSON, FICTIONAL, BlocklistSeverity.BLOCK, "test");

		this.rescanner.rescan();

		assertThat(this.reviews.findFirstByStoryIdOrderByReviewedAtDesc(storyId)).get()
				.satisfies(review -> {
					assertThat(review.getStage()).isEqualTo(ReviewStage.AUTO);
					assertThat(review.getVerdict()).isEqualTo(ReviewVerdict.REJECT);
					assertThat(review.getReasons()).contains("real_person_harm").doesNotContain(FICTIONAL);
				});
	}

	/** <b>챕터와 엔딩도 대상이다</b> (R8.5) — 세계관만 보면 다른 자리에 넣으면 통과한다. */
	@Test
	void R8_5_chapters_and_endings_are_rescanned_too() {
		UUID storyId = givenApprovedStory(CLEAN_PAYLOAD.replace("\"summarySeed\":\"시작\"",
				"\"summarySeed\":\"" + FICTIONAL + " 이 나온다\""));
		this.blocklist.register(BlocklistKind.REAL_PERSON, FICTIONAL, BlocklistSeverity.BLOCK, "test");

		this.rescanner.rescan();

		assertThat(column(storyId, "review_status")).isEqualTo("suspended");
	}

	/** 걸리지 않은 작품은 그대로다 — 이력도 늘지 않는다. */
	@Test
	void R9_4_a_clean_story_is_left_alone() {
		UUID storyId = givenApprovedStory(CLEAN_PAYLOAD);
		this.blocklist.register(BlocklistKind.REAL_PERSON, FICTIONAL, BlocklistSeverity.BLOCK, "test");
		long before = this.reviews.findByStoryIdOrderByReviewedAtDesc(storyId).size();

		int suspended = this.rescanner.rescan();

		assertThat(suspended).isZero();
		assertThat(column(storyId, "review_status")).isEqualTo("approved");
		assertThat(this.reviews.findByStoryIdOrderByReviewedAtDesc(storyId)).hasSize((int) before);
	}

	/**
	 * <b>두 번 돌아도 한 번만 센다.</b>
	 *
	 * <p>이미 내려간 작품은 승인작이 아니므로 다음 회차의 대상이 아니다 — 회차마다 같은 작품을
	 * 다시 세면 <b>지표가 실제로 일어난 일과 달라진다.</b>
	 */
	@Test
	void R9_4_a_second_pass_does_not_count_the_same_story_again() {
		givenApprovedStory(CLEAN_PAYLOAD.replace("봄의 학교에서 시작한다.", FICTIONAL + " 이 나온다."));
		this.blocklist.register(BlocklistKind.REAL_PERSON, FICTIONAL, BlocklistSeverity.BLOCK, "test");
		assertThat(this.rescanner.rescan()).isEqualTo(1);

		assertThat(this.rescanner.rescan()).isZero();
	}

	/** 블록리스트가 비어 있으면 아무것도 걸리지 않는다 — 재스캔이 스스로 판단하지 않는다. */
	@Test
	void R9_4_an_empty_blocklist_suspends_nothing() {
		UUID storyId = givenApprovedStory(CLEAN_PAYLOAD.replace("봄의 학교에서 시작한다.",
				FICTIONAL + " 이 나온다."));

		assertThat(this.rescanner.rescan()).isZero();
		assertThat(column(storyId, "review_status")).isEqualTo("approved");
	}

	/**
	 * <b>인물의 페르소나도 대상이다</b> (R8.11, §13-80).
	 *
	 * <p>페르소나는 <b>매 턴 모델에게 들어간다</b> (인물 레이어). 제출 검수는 이것을 걸지만
	 * (§13-75), 재스캔이 읽지 않으면 블록리스트가 갱신돼도 <b>이 문장만 옛 기준으로 남는다.</b>
	 */
	@Test
	void R8_11_a_character_persona_is_rescanned() {
		UUID storyId = givenApprovedStory(
				payloadWith(characterOf("이웃", "옆자리에 앉는다.", FICTIONAL + " 을 닮았다.")));
		registerFictionalEntry();

		assertThat(this.rescanner.rescan()).isEqualTo(1);
		assertThat(column(storyId, "review_status")).isEqualTo("suspended");
	}

	/** <b>인물 이름도 대상이다</b> — 타인의 상세 화면에 뜬다 (I-8, R8.11). */
	@Test
	void R8_11_a_character_name_is_rescanned() {
		UUID storyId = givenApprovedStory(
				payloadWith(characterOf(FICTIONAL, "옆자리에 앉는다.", "말수가 적다.")));
		registerFictionalEntry();

		assertThat(this.rescanner.rescan()).isEqualTo(1);
		assertThat(column(storyId, "review_status")).isEqualTo("suspended");
	}

	/**
	 * <b>선언된 플래그 이름도 대상이다</b> (R8.11, §13-80).
	 *
	 * <p>짧은 라벨을 본문과 다르게 보지 않는다 (§13-75) — 판정이 둘이 되면 무른 쪽이 곧 길이
	 * 된다. 선언된 이름은 한 번 서면 매 턴 {@code GAME_STATE} 로 나간다 (§13-76).
	 */
	@Test
	void R8_11_a_declared_flag_name_is_rescanned() {
		UUID storyId = givenApprovedStory(payloadWith("\"flags\":[\"" + FICTIONAL + "만남\"]"));
		registerFictionalEntry();

		assertThat(this.rescanner.rescan()).isEqualTo(1);
		assertThat(column(storyId, "review_status")).isEqualTo("suspended");
	}

	/**
	 * <b>진행 중 세션이 붙든 옛 버전도 대상이다</b> (I-4, §13-80).
	 *
	 * <p>세션은 생성 시 버전에 고정되므로 개정 뒤에도 <b>옛 버전의 세계관을 매 턴 모델에
	 * 싣는다.</b> 현재 버전만 훑으면 <b>읽히고 있는데 검사되지 않는 자리</b>가 남는다.
	 */
	@Test
	void S13_80_a_version_an_active_session_pinned_is_rescanned() {
		UUID authorRef = UUID.randomUUID();
		UUID draftId = this.drafts.create(authorRef).getId();
		UUID storyId = approve(authorRef, draftId,
				CLEAN_PAYLOAD.replace("봄의 학교에서 시작한다.", FICTIONAL + " 이 나온다."));
		UUID pinnedVersion = currentVersionOf(storyId);
		approve(authorRef, draftId, CLEAN_PAYLOAD);
		assertThat(currentVersionOf(storyId))
				.as("개정이 새 버전을 만들지 않았다면 이 테스트는 아무것도 확인하지 못한다")
				.isNotEqualTo(pinnedVersion);
		givenActiveSessionOn(storyId, pinnedVersion);
		registerFictionalEntry();

		assertThat(this.rescanner.rescan()).isEqualTo(1);
		assertThat(column(storyId, "review_status")).isEqualTo("suspended");
	}

	/**
	 * <b>아무도 붙들지 않는 옛 버전은 대상이 아니다</b> (§13-80).
	 *
	 * <p>재스캔이 묻는 것은 <b>지금 읽히고 있는가</b>이다. 전량을 훑으면 작성자가 고쳐서 이미
	 * 지나간 문장 때문에 <b>고친 작품이 내려간다.</b>
	 */
	@Test
	void S13_80_an_old_version_no_session_holds_is_left_alone() {
		UUID authorRef = UUID.randomUUID();
		UUID draftId = this.drafts.create(authorRef).getId();
		UUID storyId = approve(authorRef, draftId,
				CLEAN_PAYLOAD.replace("봄의 학교에서 시작한다.", FICTIONAL + " 이 나온다."));
		approve(authorRef, draftId, CLEAN_PAYLOAD);
		registerFictionalEntry();

		assertThat(this.rescanner.rescan()).isZero();
		assertThat(column(storyId, "review_status")).isEqualTo("approved");
	}

	/**
	 * 승인된 작품 하나.
	 *
	 * <p><b>여기서 단언한다.</b> 제출이 반려되면 {@code storyId} 가 비고, 그러면 실패가
	 * <b>"재스캔이 동작하지 않는다"</b> 로 보인다 — 원인은 제출 쪽인데.
	 */
	private UUID givenApprovedStory(String payload) {
		UUID authorRef = UUID.randomUUID();
		return approve(authorRef, this.drafts.create(authorRef).getId(), payload);
	}

	/**
	 * 같은 원고를 다시 내면 <b>같은 작품에 새 버전이 얹힌다</b> (R8.8, B-56).
	 *
	 * <p>제출이 반려되면 {@code storyId} 가 비고, 그러면 실패가 <b>"재스캔이 동작하지 않는다"</b>
	 * 로 보인다 — 원인은 제출 쪽인데.
	 */
	private UUID approve(UUID authorRef, UUID draftId, String payload) {
		this.drafts.save(authorRef, draftId, 5, payload);
		var outcome = this.submissions.submit(authorRef, draftId, Visibility.UNLISTED);
		assertThat(outcome.reviewStatus())
				.as("픽스처가 자동 검수에서 걸렸다 — 블록리스트가 비어 있어야 한다")
				.isEqualTo(ReviewStatus.APPROVED);
		if (!this.stories.contains(outcome.storyId())) {
			this.stories.add(outcome.storyId());
		}
		return outcome.storyId();
	}

	/** 원고에 한 벌 더 얹는다. {@code endings} 앞이면 같은 객체의 어디든 같다. */
	private static String payloadWith(String fragment) {
		return CLEAN_PAYLOAD.replace("\"endings\":", fragment + ",\n \"endings\":");
	}

	private static String characterOf(String name, String oneLine, String persona) {
		return "\"characters\":[{\"name\":\"%s\",\"oneLine\":\"%s\",\"persona\":\"%s\"}]".formatted(name,
				oneLine, persona);
	}

	private void givenActiveSessionOn(UUID storyId, UUID versionId) {
		this.sessions.saveAndFlush(PlaySession.start(PLAYER_REF, storyId, versionId, "fixed",
				"scenario", false, Instant.now()));
	}

	private void registerFictionalEntry() {
		this.blocklist.register(BlocklistKind.REAL_PERSON, FICTIONAL, BlocklistSeverity.BLOCK, "test");
	}

	private UUID currentVersionOf(UUID storyId) {
		return JdbcClient.create(this.catalog).sql("SELECT current_version_id FROM story WHERE id = ?")
				.param(storyId).query(UUID.class).optional().orElse(null);
	}

	private String column(UUID storyId, String name) {
		return JdbcClient.create(this.catalog).sql("SELECT " + name + "::text FROM story WHERE id = ?")
				.param(storyId).query(String.class).optional().orElse(null);
	}
}

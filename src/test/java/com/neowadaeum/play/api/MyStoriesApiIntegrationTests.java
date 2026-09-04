package com.neowadaeum.play.api;

import com.neowadaeum.catalog.domain.ServiceConfig;
import com.neowadaeum.catalog.repository.ServiceConfigRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.play.repository.GameStateSnapshotRepository;
import com.neowadaeum.play.repository.PlaySessionRepository;
import com.neowadaeum.play.repository.TurnRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B-36 — 내 것들 (§13.7, 화면 2.4).
 *
 * <p>가장 중요한 단언은 <b>{@code in_progress} 가 받아들여지지 않는다</b>는 것이다. §13-6 은
 * 그것을 <b>존재하지 않는 상태</b>라고 정정했고, 그대로 받으면 조회가 조용히 0건을 돌려준다.
 */
class MyStoriesApiIntegrationTests extends ContainerTestBase {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final UUID SEED_STORY = UUID.fromString("11111111-1111-4111-8111-000000000001");

	private static final UUID SEED_VERSION = UUID.fromString("11111111-1111-4111-8111-111111111111");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ServiceConfigRepository configs;

	@Autowired
	@Qualifier("catalogDataSource")
	private DataSource catalog;

	@Autowired
	private PlaySessionRepository sessions;

	@Autowired
	private TurnRepository turns;

	@Autowired
	private GameStateSnapshotRepository snapshots;

	private final List<UUID> createdStories = new ArrayList<>();

	private final List<UUID> createdDrafts = new ArrayList<>();

	@BeforeEach
	void clearPlayHistory() {
		this.snapshots.deleteAll();
		this.turns.deleteAll();
		this.sessions.deleteAll();
	}

	@AfterEach
	void removeCreated() throws SQLException {
		try (Connection connection = this.catalog.getConnection()) {
			// 원고가 작품을 가리키므로 원고를 먼저 지운다.
			for (UUID id : this.createdDrafts) {
				try (PreparedStatement statement = connection
						.prepareStatement("DELETE FROM story_draft WHERE id = ?")) {
					statement.setObject(1, id);
					statement.executeUpdate();
				}
			}
			for (UUID id : this.createdStories) {
				try (PreparedStatement statement = connection
						.prepareStatement("DELETE FROM story WHERE id = ?")) {
					statement.setObject(1, id);
					statement.executeUpdate();
				}
			}
		}
		this.createdDrafts.clear();
		this.createdStories.clear();
	}

	// ── §13-6 상태 표기 ─────────────────────────────────────

	/** <b>§13-6 — {@code in_progress} 는 존재하지 않는 상태였다.</b> 빈 목록으로 흡수하지 않는다. */
	@Test
	void S13_6_in_progress_is_rejected_not_silently_empty() throws Exception {
		MvcResult result = this.mockMvc
				.perform(get("/api/v1/me/sessions").param("status", "in_progress").with(asPlayer()))
				.andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(400);
		assertThat(JSON.readTree(result.getResponse().getContentAsString()).path("error").asString())
				.isEqualTo("VALIDATION_ERROR");
	}

	/** {@code active} 탭에 진행 중 세션이 뜬다 (R13.2 — 백분율이 아니다). */
	@Test
	void S13_6_active_lists_the_running_session_with_chapters() throws Exception {
		UUID sessionId = startSession();

		JsonNode items = mySessions("active").path("items");

		assertThat(items).hasSize(1);
		assertThat(items.get(0).path("sessionId").asString()).isEqualTo(sessionId.toString());
		assertThat(items.get(0).path("status").asString()).isEqualTo("active");
		assertThat(items.get(0).path("totalChapters").asInt()).isEqualTo(6);
		assertThat(items.get(0).has("progressPercent")).isFalse();
	}

	/** {@code completed} 탭은 끝난 것만 담는다. */
	@Test
	void S13_6_completed_lists_only_finished_sessions() throws Exception {
		startSession();

		assertThat(mySessions("completed").path("items")).isEmpty();
	}

	/**
	 * <b>버려지거나 지운 세션은 목록에 없다</b> (§13-6).
	 *
	 * <p>사용자가 이어갈 수도 되돌아볼 수도 없는 것을 보여 줄 이유가 없다.
	 */
	@Test
	void S13_6_abandoned_and_deleted_sessions_are_absent() throws Exception {
		UUID sessionId = startSession();
		this.mockMvc.perform(delete("/api/v1/sessions/{id}", sessionId).with(asPlayer()));

		assertThat(mySessions("active").path("items")).isEmpty();
		assertThat(mySessions("completed").path("items")).isEmpty();
	}

	/** <b>남의 세션은 섞이지 않는다</b> (I-3). */
	@Test
	void I3_another_member_sees_none_of_my_sessions() throws Exception {
		startSession();

		MvcResult result = this.mockMvc.perform(get("/api/v1/me/sessions").param("status", "active")
						.with(asPlayer(UUID.randomUUID())))
				.andReturn();

		assertThat(JSON.readTree(result.getResponse().getContentAsString()).path("items")).isEmpty();
	}

	/** 커서가 쪽을 잇는다 — 같은 시각이 둘일 수 있으므로 id 를 함께 본다. */
	@Test
	void S13_7_the_cursor_pages_my_sessions() throws Exception {
		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			ids.add(startSessionFor(insertStory(TEST_PLAYER_REF)));
		}

		JsonNode first = mySessions("active", null, 2);
		JsonNode second = mySessions("active", first.path("nextCursor").asString(), 2);

		List<String> seen = new ArrayList<>();
		first.path("items").forEach(item -> seen.add(item.path("sessionId").asString()));
		second.path("items").forEach(item -> seen.add(item.path("sessionId").asString()));

		assertThat(seen).doesNotHaveDuplicates().hasSize(3);
		assertThat(first.path("hasMore").asBoolean()).isTrue();
		assertThat(second.path("hasMore").asBoolean()).isFalse();
	}

	// ── 내가 만든 작품 (R13.4) ───────────────────────────────

	/**
	 * <b>작성자는 승인 전 작품도 본다.</b>
	 *
	 * <p>목록·상세가 가리는 것과 반대다 — 오히려 {@code pending} · {@code rejected} 야말로
	 * 이 화면이 보여 줘야 할 것이다 (R8.7).
	 */
	@Test
	void R13_4_my_stories_include_unapproved_ones() throws Exception {
		UUID pending = insertStory(TEST_PLAYER_REF, "private", "pending");

		JsonNode items = myStories().path("items");

		assertThat(items.valueStream().map(item -> item.path("storyId").asString()).toList())
				.contains(pending.toString());
		assertThat(items.get(0).path("reviewStatus").asString()).isNotBlank();
		assertThat(items.get(0).path("rejectReasons").isArray()).isTrue();
	}

	/**
	 * <b>§13-9 — {@code auto_rejected} 는 {@code rejected} 로 보인다.</b>
	 *
	 * <p>자동 검수인지 사람이 본 것인지는 내부 기록이며, 알리면 어느 쪽을 통과하면 되는지에
	 * 대한 단서가 된다.
	 */
	@Test
	void S13_9_auto_rejected_is_shown_as_rejected() throws Exception {
		insertStory(TEST_PLAYER_REF, "private", "auto_rejected");

		assertThat(myStories().path("items").get(0).path("reviewStatus").asString())
				.isEqualTo("rejected");
	}

	/** <b>남의 작품은 섞이지 않는다</b> (I-3). */
	@Test
	void I3_another_member_sees_none_of_my_stories() throws Exception {
		insertStory(TEST_PLAYER_REF, "private", "pending");

		MvcResult result = this.mockMvc.perform(get("/api/v1/me/stories")
						.with(asPlayer(UUID.randomUUID())))
				.andReturn();

		assertThat(JSON.readTree(result.getResponse().getContentAsString()).path("items")).isEmpty();
	}

	/** 플레이 횟수가 play 스토어에서 온다 (§5.3). */
	@Test
	void R13_4_play_count_comes_from_the_play_store() throws Exception {
		UUID storyId = insertStory(TEST_PLAYER_REF, "public", "approved");
		startSessionFor(storyId);

		JsonNode item = myStories().path("items").valueStream()
				.filter(node -> storyId.toString().equals(node.path("storyId").asString()))
				.findFirst()
				.orElseThrow();

		assertThat(item.path("playCount").asLong()).isEqualTo(1);
	}

	/**
	 * <b>쪽 전체가 한 번에 세어진다</b> (#351, §15).
	 *
	 * <p>줄마다 물으면 20줄짜리 목록이 <b>조회 21번</b>이 된다. 같은 목록의 검수 시각(#290)과
	 * 원고 id(#340)는 이미 쪽 단위였고 <b>이 값만 규칙이 달랐다.</b>
	 *
	 * <p>확인하는 것은 쿼리 개수가 아니라 <b>세는 방식을 바꾸고도 값이 같은가</b> 다 — 묶어서
	 * 물으면 세션이 없는 줄은 결과에 아예 나오지 않으므로, 그 자리가 <b>0 으로 채워지는지</b>가
	 * 바뀐 부분이다.
	 */
	@Test
	void S351_play_counts_are_counted_per_page_not_per_row() throws Exception {
		UUID played = insertStory(TEST_PLAYER_REF, "public", "approved");
		UUID untouched = insertStory(TEST_PLAYER_REF, "public", "approved");
		startSessionFor(played);

		assertThat(itemOf(played).path("playCount").asLong()).isEqualTo(1);
		// 아직 아무도 플레이하지 않은 작품은 묶음 조회의 결과에 없다 — 그것이 0 이다.
		assertThat(itemOf(untouched).path("playCount").asLong()).isZero();
	}

	/**
	 * <b>#340 — 작품에서 원고로 가는 길은 서버가 준다.</b>
	 *
	 * <p>이 값이 없으면 "이어서 작성"도 {@code getDraftReview} 도 부를 수 없고, 화면이 제목
	 * 같은 것으로 짝지으면 같은 제목의 원고가 둘일 때 <b>조용히 남의 것으로 보낸다.</b>
	 */
	@Test
	void S340_my_story_carries_the_draft_that_published_it() throws Exception {
		UUID storyId = insertStory(TEST_PLAYER_REF, "private", "rejected");
		UUID draftId = insertDraft(TEST_PLAYER_REF, storyId);

		assertThat(itemOf(storyId).path("draftId").asString()).isEqualTo(draftId.toString());
	}

	/**
	 * <b>#340 · §13-5 — 원고 없이 존재하는 작품이 있다.</b>
	 *
	 * <p>미리보기는 임시 작품을 발행하고 원고와 잇지 않는다. 그런 줄에 아무 원고나 붙이면
	 * 화면은 <b>남의 원고를 열게</b> 된다 — 없다고 말하는 편이 사실이다.
	 */
	@Test
	void S13_5_a_story_without_a_draft_reports_null() throws Exception {
		UUID storyId = insertStory(TEST_PLAYER_REF, "private", "draft");

		assertThat(itemOf(storyId).path("draftId").isNull()).isTrue();
	}

	/** 토큰 없이는 401 이다. */
	@Test
	void S34_both_tabs_require_a_token() throws Exception {
		this.mockMvc.perform(get("/api/v1/me/sessions").param("status", "active"))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));
		this.mockMvc.perform(get("/api/v1/me/stories"))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));
	}

	// ── 보조 ────────────────────────────────────────────────


	/**
	 * #281 — 이 화면의 Footer 도 고지를 상시 표시한다. 싣지 않으면 클라이언트가 화면마다
	 * {@code /landing} 을 한 번 더 부르고, 두 응답의 캐시 수명이 갈리면 <b>같은 화면에서 다른
	 * 문구</b>가 보인다 (R11.1).
	 */
	@Test
	void R11_1_my_sessions_carries_the_notice_text() throws Exception {
		assertThat(mySessions("active").path("noticeText").asString()).isEqualTo(NOTICE);
	}

	@Test
	void R11_1_my_stories_carries_the_notice_text() throws Exception {
		assertThat(myStories().path("noticeText").asString()).isEqualTo(NOTICE);
	}

	/** 문구가 없으면 <b>빈 문자열이 아니라</b> 실패다 — 고지 없는 상태가 정상으로 보이면 안 된다. */
	@Test
	void R11_1_my_sessions_fails_when_the_notice_is_missing() throws Exception {
		this.configs.deleteById(NOTICE_KEY);

		MvcResult result = this.mockMvc
				.perform(get("/api/v1/me/sessions").param("status", "active").with(asPlayer()))
				.andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(500);
	}

	private JsonNode mySessions(String status) throws Exception {
		return mySessions(status, null, null);
	}

	private JsonNode mySessions(String status, String cursor, Integer limit) throws Exception {
		var request = get("/api/v1/me/sessions").param("status", status).with(asPlayer());
		if (cursor != null && !cursor.isBlank()) {
			request = request.param("cursor", cursor);
		}
		if (limit != null) {
			request = request.param("limit", String.valueOf(limit));
		}
		MvcResult result = this.mockMvc.perform(request).andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		return JSON.readTree(result.getResponse().getContentAsString());
	}

	private JsonNode myStories() throws Exception {
		MvcResult result = this.mockMvc.perform(get("/api/v1/me/stories").with(asPlayer())).andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		return JSON.readTree(result.getResponse().getContentAsString());
	}

	private UUID startSession() throws Exception {
		return startSessionFor(SEED_STORY);
	}

	private UUID startSessionFor(UUID storyId) throws Exception {
		MvcResult result = this.mockMvc.perform(post("/api/v1/stories/{storyId}/sessions", storyId)
						.with(asPlayer()))
				.andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString())
				.path("sessionId").asString());
	}

	private JsonNode itemOf(UUID storyId) throws Exception {
		return myStories().path("items").valueStream()
				.filter(node -> storyId.toString().equals(node.path("storyId").asString()))
				.findFirst()
				.orElseThrow();
	}

	/** 제출이 만드는 연결({@code DraftService.linkStory})을 그대로 흉내 낸다. */
	private UUID insertDraft(UUID authorRef, UUID storyId) {
		UUID id = UUID.randomUUID();
		try (Connection connection = this.catalog.getConnection();
				PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO story_draft (id, author_ref, story_id, step, payload)
						VALUES (?, ?, ?, 5, '{}'::JSONB)
						""")) {
			statement.setObject(1, id);
			statement.setObject(2, authorRef);
			statement.setObject(3, storyId);
			statement.executeUpdate();
		}
		catch (SQLException ex) {
			throw new IllegalStateException(ex);
		}
		this.createdDrafts.add(id);
		return id;
	}

	private UUID insertStory(UUID authorRef) {
		return insertStory(authorRef, "public", "approved");
	}

	/** 작성자 화면은 승인 전 작품을 보여 줘야 하므로 그런 데이터를 만들 수 있어야 한다. */
	private UUID insertStory(UUID authorRef, String visibility, String reviewStatus) {
		UUID id = UUID.randomUUID();
		try (Connection connection = this.catalog.getConnection();
				PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO story (id, slug, title, short_desc, description, world_intro,
						                   author_type, author_ref, visibility, review_status,
						                   current_version_id, published_at, created_at)
						VALUES (?, ?, '내 작품', '한 줄', '설명', '세계관', 'user', ?, ?, ?, ?, ?, ?)
						""")) {
			statement.setObject(1, id);
			statement.setString(2, "mine-" + id);
			statement.setObject(3, authorRef);
			statement.setString(4, visibility);
			statement.setString(5, reviewStatus);
			statement.setObject(6, SEED_VERSION);
			statement.setTimestamp(7, java.sql.Timestamp.from(Instant.parse("2026-08-20T00:00:00Z")));
			statement.setTimestamp(8, java.sql.Timestamp.from(Instant.parse("2026-08-20T00:00:00Z")));
			statement.executeUpdate();
		}
		catch (SQLException ex) {
			throw new IllegalStateException(ex);
		}
		this.createdStories.add(id);
		return id;
	}
}

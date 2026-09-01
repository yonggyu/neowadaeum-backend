package com.neowadaeum.play.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.repository.GameStateSnapshotRepository;
import com.neowadaeum.play.repository.PlaySessionRepository;
import com.neowadaeum.play.repository.TurnRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
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
 * B-17(2/2) — <b>{@code sessionState} 5종</b>과 마지막 턴 복원 (§13.4, §4.7).
 *
 * <p>다섯 상태를 전부 만들어 본다. 하나라도 만들 수 없으면 그 상태는 <b>운영에서 처음</b>
 * 나타나고, 그때 클라이언트가 모르는 값을 받는다.
 */
class SessionResumeIntegrationTests extends ContainerTestBase {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final UUID SEED_STORY = UUID.fromString("11111111-1111-4111-8111-000000000001");

	private static final UUID SEED_VERSION = UUID.fromString("11111111-1111-4111-8111-111111111111");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	@Qualifier("catalogDataSource")
	private DataSource catalog;

	@Autowired
	private PlaySessionRepository sessions;

	@Autowired
	private TurnRepository turns;

	@Autowired
	private GameStateSnapshotRepository snapshots;

	@BeforeEach
	void clearPlayHistory() {
		this.snapshots.deleteAll();
		this.turns.deleteAll();
		this.sessions.deleteAll();
	}

	/** 시드 작품의 상태를 되돌린다 — 다른 테스트가 이 작품 위에서 돈다. */
	@AfterEach
	void restoreSeedStory() throws SQLException {
		updateSeedStory("approved", SEED_VERSION);
	}

	// ── sessionState 5종 ────────────────────────────────────

	/** §4.7 — 방금 시작한 세션은 이어갈 수 있다. */
	@Test
	void S4_7_a_fresh_session_is_valid() throws Exception {
		UUID sessionId = startSession();

		JsonNode resume = resume(sessionId);

		assertThat(resume.path("sessionState").asString()).isEqualTo("valid");
		assertThat(resume.path("chapterNo").asInt()).isEqualTo(1);
		assertThat(resume.path("totalChapters").asInt()).isEqualTo(6);
		assertThat(resume.path("turnNo").asInt()).isEqualTo(1);
		assertThat(resume.path("canViewHistory").asBoolean()).isTrue();
		assertThat(resume.path("lastSceneVisual").isNull()).as("P3 — 아직 발행하지 않는다").isTrue();
	}

	/** 지운 세션은 {@code deleted} 다 (§13.4). */
	@Test
	void S13_4_a_deleted_session_reports_deleted() throws Exception {
		UUID sessionId = startSession();
		this.mockMvc.perform(delete("/api/v1/sessions/{id}", sessionId).with(asPlayer()));

		assertThat(resume(sessionId).path("sessionState").asString()).isEqualTo("deleted");
	}

	/**
	 * 만료된 세션은 {@code expired} 다 (§4.7).
	 *
	 * <p>90일을 기다릴 수 없으므로 상태를 직접 만든다. <b>그 상태로 바꾸는 배치는 아직 없다</b>
	 * (B-61) — 그렇다고 판정을 미루면 배치가 생기는 날 처음 확인하게 된다.
	 */
	@Test
	void S4_7_an_expired_session_reports_expired() throws Exception {
		UUID sessionId = startSession();
		expire(sessionId);

		assertThat(resume(sessionId).path("sessionState").asString()).isEqualTo("expired");
	}

	/** <b>R2.1 — 새 버전이 발행되면 {@code version_changed} 다.</b> */
	@Test
	void R2_1_a_new_current_version_reports_version_changed() throws Exception {
		UUID sessionId = startSession();
		updateSeedStory("approved", UUID.randomUUID());

		assertThat(resume(sessionId).path("sessionState").asString()).isEqualTo("version_changed");
	}

	/** <b>R8.10 · R13.3 — 정지된 작품은 {@code story_suspended} 다.</b> 클라이언트가 읽기 전용을 안내한다. */
	@Test
	void R8_10_a_suspended_story_reports_story_suspended() throws Exception {
		UUID sessionId = startSession();
		updateSeedStory("suspended", SEED_VERSION);

		assertThat(resume(sessionId).path("sessionState").asString()).isEqualTo("story_suspended");
	}

	/**
	 * <b>여러 조건이 겹치면 사용자가 할 수 있는 일이 가장 적은 쪽이 이긴다.</b>
	 *
	 * <p>지운 세션에 "버전이 바뀌었습니다"를 안내하면 사용자는 자기가 무엇을 했는지 헷갈린다.
	 */
	@Test
	void S4_7_deleted_wins_over_version_changed() throws Exception {
		UUID sessionId = startSession();
		this.mockMvc.perform(delete("/api/v1/sessions/{id}", sessionId).with(asPlayer()));
		updateSeedStory("suspended", UUID.randomUUID());

		assertThat(resume(sessionId).path("sessionState").asString()).isEqualTo("deleted");
	}

	// ── 소유와 인증 ──────────────────────────────────────────

	/** <b>남의 세션은 없는 것과 구분되지 않는다</b> (I-3). */
	@Test
	void I3_another_members_session_is_not_found() throws Exception {
		UUID sessionId = startSession();

		assertThat(statusOf(get("/api/v1/sessions/{id}/resume", sessionId), UUID.randomUUID()))
				.isEqualTo(404);
		assertThat(statusOf(get("/api/v1/sessions/{id}/current", sessionId), UUID.randomUUID()))
				.isEqualTo(404);
	}

	/** 토큰 없이는 401 이다. */
	@Test
	void S34_resume_and_current_require_a_token() throws Exception {
		this.mockMvc.perform(get("/api/v1/sessions/{id}/resume", UUID.randomUUID()))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));
		this.mockMvc.perform(get("/api/v1/sessions/{id}/current", UUID.randomUUID()))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));
	}

	// ── 마지막 턴 복원 ───────────────────────────────────────

	/** §13.4 — 턴 응답과 같은 형태로 온다. */
	@Test
	void S13_4_current_returns_the_last_turn_in_the_turn_shape() throws Exception {
		UUID sessionId = startSession();

		JsonNode turn = current(sessionId);

		assertThat(turn.path("turnNo").asInt()).isEqualTo(1);
		assertThat(turn.path("chapterNo").asInt()).isEqualTo(1);
		assertThat(turn.path("choices")).isNotEmpty();
		assertThat(turn.path("progressHint").asString()).contains("Chapter 1");
		assertThat(turn.path("isAiGenerated").asBoolean()).isTrue();
		assertThat(turn.path("isEnding").asBoolean()).isFalse();
	}

	/**
	 * <b>지운 세션도 마지막 장면을 돌려준다.</b>
	 *
	 * <p>다시 그리기는 진행 중을 요구하지 않는다 — 끝난 이야기의 마지막 장면이 새로고침에서
	 * 사라지면 안 된다. 이어갈 수 있는지는 {@code resume} 이 답한다.
	 */
	@Test
	void S13_4_current_answers_even_when_the_session_is_no_longer_active() throws Exception {
		UUID sessionId = startSession();
		this.mockMvc.perform(delete("/api/v1/sessions/{id}", sessionId).with(asPlayer()));

		assertThat(current(sessionId).path("turnNo").asInt()).isEqualTo(1);
	}

	/** 턴이 하나도 없는 세션은 404 다 — 돌려줄 장면이 없다. */
	@Test
	void S13_4_current_without_any_turn_is_not_found() throws Exception {
		UUID sessionId = startSession();
		this.turns.deleteAll();

		assertThat(statusOf(get("/api/v1/sessions/{id}/current", sessionId), TEST_PLAYER_REF))
				.isEqualTo(404);
	}

	// ── 보조 ────────────────────────────────────────────────

	private UUID startSession() throws Exception {
		MvcResult result = this.mockMvc.perform(post("/api/v1/stories/{storyId}/sessions", SEED_STORY)
						.with(asPlayer()))
				.andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString())
				.path("sessionId").asString());
	}

	private JsonNode resume(UUID sessionId) throws Exception {
		return body(get("/api/v1/sessions/{id}/resume", sessionId));
	}

	private JsonNode current(UUID sessionId) throws Exception {
		return body(get("/api/v1/sessions/{id}/current", sessionId));
	}

	private JsonNode body(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
		MvcResult result = this.mockMvc.perform(((org.springframework.test.web.servlet.request
				.MockHttpServletRequestBuilder) request).with(asPlayer())).andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		return JSON.readTree(result.getResponse().getContentAsString());
	}

	private int statusOf(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
			UUID playerRef) throws Exception {
		return this.mockMvc.perform(request.with(asPlayer(playerRef))).andReturn().getResponse().getStatus();
	}

	/** 90일을 기다릴 수 없으므로 상태를 직접 만든다. 그 상태로 바꾸는 배치는 B-61 이다. */
	private void expire(UUID sessionId) {
		PlaySession session = this.sessions.findById(sessionId).orElseThrow();
		try {
			java.lang.reflect.Field status = PlaySession.class.getDeclaredField("status");
			status.setAccessible(true);
			status.set(session, com.neowadaeum.play.domain.SessionStatus.EXPIRED);
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
		this.sessions.saveAndFlush(session);
	}

	/** 작품 쪽 사실을 바꾼다 — 정지와 새 버전은 catalog 의 일이고 play 에는 손잡이가 없다. */
	private void updateSeedStory(String reviewStatus, UUID currentVersionId) throws SQLException {
		try (Connection connection = this.catalog.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"UPDATE story SET review_status = ?, current_version_id = ? WHERE id = ?")) {
			statement.setString(1, reviewStatus);
			statement.setObject(2, currentVersionId);
			statement.setObject(3, SEED_STORY);
			statement.executeUpdate();
		}
	}
}

package com.neowadaeum.play.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.play.repository.GameStateSnapshotRepository;
import com.neowadaeum.play.repository.PlaySessionRepository;
import com.neowadaeum.play.repository.TurnRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-9-2 (#65) — <b>밖에서 플레이가 된다.</b>
 *
 * <p>S-9-1 이 파이프라인을 만들었고, 여기서 HTTP 로 열린다. 시작 → 턴 → 엔딩이 실제 요청으로
 * 돌고, 잘못된 요청이 <b>상태를 바꾸지 않고</b> 거절되는지 함께 본다.
 *
 * <p>테스트가 같은 {@code playerRef} 를 쓰므로 "작품당 active 세션 1개"(§13-9)가 테스트 사이에
 * 걸린다 — 매 테스트 전에 이 작품의 플레이 기록을 비운다.
 *
 * <p><b>모든 요청이 토큰을 싣는다</b> (B-12, #34). 고정 {@code player_ref} 우회는 사라졌고,
 * {@link com.neowadaeum.ContainerTestBase#asPlayer()} 가 실제 발급기의 토큰을 헤더로 붙인다.
 */
class PlayApiIntegrationTests extends ContainerTestBase {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final UUID SEED_STORY = UUID.fromString("11111111-1111-4111-8111-000000000001");

	@Autowired
	private MockMvc mockMvc;

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

	// ── 정상 경로 ───────────────────────────────────────────

	/**
	 * <b>토큰 없이는 거절된다</b> (B-12, #34).
	 *
	 * <p>이 자리에는 CSRF 토큰 검사가 있었다. 그때는 <b>인증이 우회된 상태</b>였고, 남의 사이트가
	 * 고정 {@code player_ref} 의 세션을 만드는 것을 CSRF 가 막았다. 이제 자격 증명이
	 * {@code Authorization} 헤더로만 오므로 브라우저가 자동으로 실어 보내는 것이 없고,
	 * <b>막아야 할 것은 위조가 아니라 인증 부재</b>다.
	 */
	@Test
	void S34_a_request_without_a_token_is_rejected() throws Exception {
		this.mockMvc.perform(post("/api/v1/stories/{storyId}/sessions", SEED_STORY))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401))
				.andExpect(result -> assertThat(errorOf(result)).isEqualTo("UNAUTHENTICATED"));

		assertThat(this.sessions.count()).as("거절된 요청이 세션을 만들면 안 된다").isZero();
	}

	/** 위조·만료 토큰도 같은 응답이다. 어느 쪽이 틀렸는지 알려주지 않는다 (S-6). */
	@Test
	void S34_a_forged_token_is_indistinguishable_from_no_token() throws Exception {
		this.mockMvc.perform(post("/api/v1/stories/{storyId}/sessions", SEED_STORY)
						.header("Authorization", "Bearer not.a.real.token"))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401))
				.andExpect(result -> assertThat(errorOf(result)).isEqualTo("UNAUTHENTICATED"));

		assertThat(this.sessions.count()).isZero();
	}

	/**
	 * <b>남의 세션에 턴을 더할 수 없다.</b>
	 *
	 * <p>우회가 있던 동안에는 확인할 수 없던 성질이다 — 모든 요청이 같은 {@code playerRef} 였다.
	 */
	@Test
	void S34_another_member_cannot_advance_someone_elses_session() throws Exception {
		JsonNode start = startSession();
		UUID sessionId = UUID.fromString(start.path("sessionId").asString());
		String choiceId = start.path("turn").path("choices").get(0).path("choiceId").asString();

		this.mockMvc.perform(post("/api/v1/sessions/{sessionId}/turns", sessionId)
						.with(asPlayer(UUID.randomUUID()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"choiceId\":\"%s\",\"turnNo\":1}".formatted(choiceId)))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(404));

		assertThat(this.sessions.findById(sessionId).orElseThrow().getTurnNo())
				.as("남의 요청이 세션을 움직이면 안 된다")
				.isEqualTo(1);
	}

	/** §4.2 — 세션 시작과 함께 턴 1 이 온다. 별도 요청이 필요 없다. */
	@Test
	void S4_2_starting_a_session_returns_the_first_turn() throws Exception {
		JsonNode body = startSession();

		assertThat(body.path("sessionId").asString()).isNotBlank();
		assertThat(body.path("turn").path("turnNo").asInt()).isEqualTo(1);
		assertThat(body.path("turn").path("chapterNo").asInt()).isEqualTo(1);
		assertThat(body.path("turn").path("choices")).isNotEmpty();
		assertThat(body.path("turn").path("isEnding").asBoolean()).isFalse();

		// R7.5 — progressHint 만 준다. progressPercent 는 만들지 않는다.
		assertThat(body.path("turn").path("progressHint").asString()).contains("Chapter 1");
		assertThat(body.path("turn").has("progressPercent")).isFalse();

		// I-1 — choiceId 는 서버가 발급한다. §13-9 형식이다.
		assertThat(body.path("turn").path("choices").get(0).path("choiceId").asString()).startsWith("1-1-");

		// I-11 · §13-3 — disabled 는 서버 판정이며 P0 에서는 항상 false 다. 필드는 유지한다.
		assertThat(body.path("turn").path("choices").get(0).path("disabled").asBoolean()).isFalse();
		assertThat(body.path("turn").path("choices").get(0).has("disabledReason")).isTrue();
	}

	/** <b>밖에서 끝까지 플레이된다.</b> M1 이 HTTP 경로에서 성립하는 지점이다. */
	@Test
	void S9_2_a_session_can_be_played_to_an_ending_over_http() throws Exception {
		JsonNode start = startSession();
		UUID sessionId = UUID.fromString(start.path("sessionId").asString());
		JsonNode turn = start.path("turn");

		for (int guard = 0; guard < 45 && !turn.path("isEnding").asBoolean(); guard++) {
			turn = advance(sessionId, turn.path("choices").get(0).path("choiceId").asString(),
					turn.path("turnNo").asInt(), 200);
		}

		assertThat(turn.path("isEnding").asBoolean()).as("45턴 안에 끝나지 않았다").isTrue();
		assertThat(turn.path("choices")).isEmpty();
		assertThat(turn.path("endingId").asString()).isNotBlank();
		// 시크릿을 뺀 보이는 엔딩 넷 (R7.11, B-45).
		assertThat(turn.path("totalEndings").asInt()).isEqualTo(4);
	}

	// ── 거절 경로 — 상태를 바꾸지 않는다 (R6.6) ─────────────

	/** §13-9 — 작품당 active 세션은 1개다. */
	@Test
	void S13_9_starting_a_second_session_for_the_same_story_is_rejected() throws Exception {
		startSession();

		this.mockMvc.perform(post("/api/v1/stories/{storyId}/sessions", SEED_STORY).with(asPlayer()))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(409))
				.andExpect(result -> assertThat(errorOf(result)).isEqualTo("SESSION_ALREADY_ACTIVE"));
	}

	/** R6.1 — {@code turnNo} 불일치는 409 이며 <b>현재 턴 상태가 함께 온다.</b> 맞출 근거가 있어야 한다. */
	@Test
	void R6_1_turn_number_mismatch_returns_conflict_with_the_current_state() throws Exception {
		JsonNode start = startSession();
		UUID sessionId = UUID.fromString(start.path("sessionId").asString());
		String choiceId = start.path("turn").path("choices").get(0).path("choiceId").asString();

		JsonNode error = advance(sessionId, choiceId, 99, 409);

		assertThat(error.path("error").asString()).isEqualTo("TURN_CONFLICT");
		assertThat(error.path("details").path("currentTurnNo").asInt()).isEqualTo(1);

		assertThat(this.sessions.findById(sessionId).orElseThrow().getTurnNo())
				.as("실패한 요청이 세션을 움직이면 안 된다 (R6.6)")
				.isEqualTo(1);
	}

	/** I-1 — 서버가 발급하지 않은 {@code choiceId} 는 거절된다. */
	@Test
	void I1_unknown_choice_id_is_rejected() throws Exception {
		JsonNode start = startSession();
		UUID sessionId = UUID.fromString(start.path("sessionId").asString());

		JsonNode error = advance(sessionId, "1-1-deadbeef", 1, 400);

		assertThat(error.path("error").asString()).isEqualTo("INVALID_CHOICE");
	}

	/**
	 * I-1 · §13-9 — <b>이전 턴의 {@code choiceId} 는 재사용할 수 없다.</b>
	 *
	 * <p>식별자에 턴 번호가 들어 있어 구조적으로 막힌다. 재사용이 통하면 사용자가 지나간 분기를
	 * 다시 고를 수 있고, 그러면 서버가 발급한다는 규칙이 의미를 잃는다.
	 */
	@Test
	void S13_9_a_choice_id_from_an_earlier_turn_cannot_be_reused() throws Exception {
		JsonNode start = startSession();
		UUID sessionId = UUID.fromString(start.path("sessionId").asString());
		String firstTurnChoiceId = start.path("turn").path("choices").get(0).path("choiceId").asString();

		advance(sessionId, firstTurnChoiceId, 1, 200);

		JsonNode error = advance(sessionId, firstTurnChoiceId, 2, 400);

		assertThat(error.path("error").asString()).isEqualTo("INVALID_CHOICE");
	}

	/** 종료된 세션에는 턴을 더할 수 없다. */
	@Test
	void S9_2_a_completed_session_rejects_further_turns() throws Exception {
		JsonNode start = startSession();
		UUID sessionId = UUID.fromString(start.path("sessionId").asString());
		JsonNode turn = start.path("turn");

		for (int guard = 0; guard < 45 && !turn.path("isEnding").asBoolean(); guard++) {
			turn = advance(sessionId, turn.path("choices").get(0).path("choiceId").asString(),
					turn.path("turnNo").asInt(), 200);
		}

		JsonNode error = advance(sessionId, "1-1-deadbeef", turn.path("turnNo").asInt(), 403);

		assertThat(error.path("error").asString()).isEqualTo("FORBIDDEN");
	}

	/** 남의 세션은 "없는 것"과 구분되지 않는다. 존재 여부가 새면 세션 id 를 훑을 수 있다. */
	@Test
	void S9_2_an_unknown_session_is_not_found() throws Exception {
		JsonNode error = advance(UUID.randomUUID(), "1-1-deadbeef", 1, 404);

		assertThat(error.path("error").asString()).isEqualTo("NOT_FOUND");
	}

	/** §7.4 · S-6 — 에러 응답에 스택트레이스·SQL·내부 경로가 없다. */
	@Test
	void S7_4_error_responses_do_not_leak_internals() throws Exception {
		JsonNode start = startSession();
		UUID sessionId = UUID.fromString(start.path("sessionId").asString());

		String raw = advance(sessionId, "1-1-deadbeef", 1, 400).toString();

		assertThat(raw).doesNotContain("com.neowadaeum", "SELECT", "Exception", "/mnt/", "at ");
	}

	// ── 보조 ────────────────────────────────────────────────

	private JsonNode startSession() throws Exception {
		MvcResult result = this.mockMvc.perform(post("/api/v1/stories/{storyId}/sessions", SEED_STORY).with(asPlayer()))
				.andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		return JSON.readTree(result.getResponse().getContentAsString());
	}

	private JsonNode advance(UUID sessionId, String choiceId, int turnNo, int expectedStatus) throws Exception {
		MvcResult result = this.mockMvc.perform(post("/api/v1/sessions/{sessionId}/turns", sessionId)
						.with(asPlayer())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"choiceId\":\"%s\",\"turnNo\":%d}".formatted(choiceId, turnNo)))
				.andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
		return JSON.readTree(result.getResponse().getContentAsString());
	}

	private static String errorOf(org.springframework.test.web.servlet.MvcResult result) throws Exception {
		return JSON.readTree(result.getResponse().getContentAsString()).path("error").asString();
	}
}

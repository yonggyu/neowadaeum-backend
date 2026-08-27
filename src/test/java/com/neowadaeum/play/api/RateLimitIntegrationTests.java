package com.neowadaeum.play.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.common.support.RateLimitProperties;
import com.neowadaeum.common.support.RateLimiter;
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
 * B-38 — <b>429 세 종류가 코드로 구분된다</b> (§15, §11, S-8).
 *
 * <p>같은 HTTP 상태지만 클라이언트가 할 일이 다르다 — 잠시 기다린다 / 조금 늦춘다 / 내일 온다.
 * 하나로 합치면 그 구분이 사라지고, 사용자는 언제 다시 시도해야 하는지 알 수 없다.
 */
class RateLimitIntegrationTests extends ContainerTestBase {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final UUID SEED_STORY = UUID.fromString("11111111-1111-4111-8111-000000000001");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private RateLimiter rateLimiter;

	@Autowired
	private RateLimitProperties limits;

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

	/** §15 — 분당 한도를 넘기면 {@code RATE_LIMITED} 이며 언제 다시 올지 알려준다. */
	@Test
	void S15_exceeding_the_per_minute_limit_is_rate_limited() throws Exception {
		UUID playerRef = UUID.randomUUID();
		exhaust("turn-minute", playerRef, this.limits.turnPerMinute());

		JsonNode error = failingTurn(playerRef);

		assertThat(error.path("error").asString()).isEqualTo("RATE_LIMITED");
		assertThat(error.path("details").path("retryAfterSeconds").asLong()).isPositive();
	}

	/**
	 * <b>일일 한도는 분당 한도보다 먼저 본다.</b>
	 *
	 * <p>오늘 다 쓴 사용자에게 "조금 늦춰 주세요"를 안내하면 <b>그 안내대로 해도 통과하지 못한다.</b>
	 */
	@Test
	void S15_the_daily_quota_wins_over_the_per_minute_limit() throws Exception {
		UUID playerRef = UUID.randomUUID();
		exhaust("turn-day", playerRef, this.limits.turnPerDay());
		exhaust("turn-minute", playerRef, this.limits.turnPerMinute());

		assertThat(failingTurn(playerRef).path("error").asString()).isEqualTo("QUOTA_EXCEEDED");
	}

	/**
	 * <b>한도에 걸린 요청은 세션을 움직이지 않는다</b> (R6.6).
	 *
	 * <p>거절이 상태를 바꾸면 사용자는 실패했는데 턴이 넘어간 화면을 본다.
	 */
	@Test
	void R6_6_a_limited_request_does_not_move_the_session() throws Exception {
		UUID sessionId = startSession();
		exhaust("turn-minute", TEST_PLAYER_REF, this.limits.turnPerMinute());

		this.mockMvc.perform(post("/api/v1/sessions/{id}/turns", sessionId).with(asPlayer())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"choiceId\":\"1-1-deadbeef\",\"turnNo\":1}"))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(429));

		assertThat(this.sessions.findById(sessionId).orElseThrow().getTurnNo()).isEqualTo(1);
		assertThat(this.turns.count()).isEqualTo(1);
	}

	/**
	 * <b>S-8 — 인증 경로는 IP 로 센다.</b>
	 *
	 * <p>인증 전이라 계정 기준 한도를 걸 수 없다. 걸지 않으면 ID 토큰을 무작위로 던져 보는
	 * 요청이 무제한이 된다.
	 */
	@Test
	void S8_the_auth_path_is_limited_by_ip() throws Exception {
		for (int attempt = 0; attempt < this.limits.authPerMinutePerIp(); attempt++) {
			this.mockMvc.perform(post("/api/v1/auth/refresh")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"refreshToken\":\"not-a-token\"}"));
		}

		MvcResult result = this.mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"refreshToken\":\"not-a-token\"}"))
				.andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(429);
		assertThat(JSON.readTree(result.getResponse().getContentAsString()).path("error").asString())
				.isEqualTo("RATE_LIMITED");
	}

	/** 한도 안에서는 통과한다 — 막는 것이 목적이 아니라 폭주를 막는 것이 목적이다. */
	@Test
	void S15_requests_within_the_limit_pass() {
		UUID playerRef = UUID.randomUUID();

		for (int attempt = 0; attempt < this.limits.turnPerMinute(); attempt++) {
			assertThat(this.rateLimiter.tryAcquire("probe", playerRef.toString(),
					this.limits.turnPerMinute(), RateLimitProperties.MINUTE)).isTrue();
		}
		assertThat(this.rateLimiter.tryAcquire("probe", playerRef.toString(),
				this.limits.turnPerMinute(), RateLimitProperties.MINUTE)).isFalse();
	}

	/** 계정이 다르면 한도도 다르다 — 한 사용자가 다른 사용자를 막지 못한다. */
	@Test
	void S15_the_limit_is_per_account() {
		UUID first = UUID.randomUUID();
		exhaust("probe-scope", first, this.limits.turnPerMinute());

		assertThat(this.rateLimiter.tryAcquire("probe-scope", UUID.randomUUID().toString(),
				this.limits.turnPerMinute(), RateLimitProperties.MINUTE)).isTrue();
	}

	// ── 보조 ────────────────────────────────────────────────

	/** 창을 미리 소진한다 — 실제로 10번 턴을 만들면 Provider 를 열 번 부르게 된다. */
	private void exhaust(String scope, UUID playerRef, int limit) {
		java.time.Duration window = "turn-day".equals(scope) ? RateLimitProperties.DAY
				: RateLimitProperties.MINUTE;
		for (int attempt = 0; attempt < limit; attempt++) {
			this.rateLimiter.tryAcquire(scope, playerRef.toString(), limit, window);
		}
	}

	private UUID startSession() throws Exception {
		MvcResult result = this.mockMvc.perform(post("/api/v1/stories/{storyId}/sessions", SEED_STORY)
						.with(asPlayer()))
				.andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString())
				.path("sessionId").asString());
	}

	/** 세션 없이 한도만 확인한다 — 한도는 세션 검증보다 뒤, 생성보다 앞이다. */
	private JsonNode failingTurn(UUID playerRef) throws Exception {
		UUID sessionId = startSessionAs(playerRef);
		MvcResult result = this.mockMvc.perform(post("/api/v1/sessions/{id}/turns", sessionId)
						.with(asPlayer(playerRef))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"choiceId\":\"1-1-deadbeef\",\"turnNo\":1}"))
				.andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(429);
		return JSON.readTree(result.getResponse().getContentAsString());
	}

	private UUID startSessionAs(UUID playerRef) throws Exception {
		MvcResult result = this.mockMvc.perform(post("/api/v1/stories/{storyId}/sessions", SEED_STORY)
						.param("restart", "true")
						.with(asPlayer(playerRef)))
				.andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString())
				.path("sessionId").asString());
	}
}

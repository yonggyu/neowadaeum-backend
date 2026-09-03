package com.neowadaeum.play.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.common.support.RateLimitProperties;
import com.neowadaeum.common.support.RateLimitWindows;
import com.neowadaeum.common.support.RateLimiter;
import com.neowadaeum.play.repository.GameStateSnapshotRepository;
import com.neowadaeum.play.repository.PlaySessionRepository;
import com.neowadaeum.play.repository.TurnRepository;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
	private org.springframework.data.redis.core.StringRedisTemplate redis;

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
	 * <p>인증 전이라 계정 기준 한도를 걸 수 없다. 걸지 않으면 리프레시 토큰을 무작위로 던져 보는
	 * 요청이 무제한이 된다.
	 *
	 * <p><b>CSRF 토큰을 싣는다</b> (ADR-0008). 이 경로는 면제에서 빠졌고 CSRF 필터가 컨트롤러보다
	 * 먼저 돌므로, 싣지 않으면 한도에 닿기 전에 403 이 나가고 <b>한도를 재는 것이 아니라 위조
	 * 방지를 재게 된다.</b>
	 */
	@Test
	void SEC8_the_auth_path_is_limited_by_ip() throws Exception {
		for (int attempt = 0; attempt < this.limits.authPerMinutePerIp(); attempt++) {
			this.mockMvc.perform(refreshAttempt());
		}

		MvcResult result = this.mockMvc.perform(refreshAttempt()).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(429);
		assertThat(JSON.readTree(result.getResponse().getContentAsString()).path("error").asString())
				.isEqualTo("RATE_LIMITED");
	}

	/** 통하지 않는 리프레시 시도 한 번. 자격 증명은 쿠키 하나이고 본문은 없다 (ADR-0008). */
	private static MockHttpServletRequestBuilder refreshAttempt() {
		String csrf = UUID.randomUUID().toString();
		return post("/api/v1/auth/refresh")
				.cookie(new Cookie("nwd_rt", "not-a-token"), new Cookie("XSRF-TOKEN", csrf))
				.header("X-XSRF-TOKEN", csrf);
	}

	/**
	 * 한도 안에서는 통과한다 — 막는 것이 목적이 아니라 폭주를 막는 것이 목적이다.
	 *
	 * <p><b>설정된 한도가 아니라 작은 한도로 잰다.</b> 창은 고정이므로(§13-28) 호출이
	 * 경계를 넘으면 카운터가 리셋된다 — 수백 번을 도는 동안 경계를 넘으면 <b>막혀야 할
	 * 호출이 통과</b>하고, 그 실패는 CI 가 느린 날에만 나타난다. 여기서 확인할 것은
	 * "한도만큼 통과하고 그 다음이 막힌다"이지 그 값이 얼마인가가 아니다.
	 */
	@Test
	void S15_requests_within_the_limit_pass() {
		UUID playerRef = UUID.randomUUID();
		int limit = 3;

		for (int attempt = 0; attempt < limit; attempt++) {
			assertThat(this.rateLimiter.tryAcquire("probe", playerRef.toString(), limit,
					RateLimitProperties.MINUTE)).isTrue();
		}
		assertThat(this.rateLimiter.tryAcquire("probe", playerRef.toString(), limit,
				RateLimitProperties.MINUTE)).isFalse();
	}

	/** 계정이 다르면 한도도 다르다 — 한 사용자가 다른 사용자를 막지 못한다. */
	@Test
	void S15_the_limit_is_per_account() {
		// 설정된 한도가 아니라 작은 한도로 잰다 — 위와 같은 이유다 (고정 창).
		int limit = 3;
		exhaust("probe-scope", UUID.randomUUID(), limit);

		assertThat(this.rateLimiter.tryAcquire("probe-scope", UUID.randomUUID().toString(), limit,
				RateLimitProperties.MINUTE)).isTrue();
	}

	// ── 보조 ────────────────────────────────────────────────

	/**
	 * 창을 미리 소진한다 — 실제로 10번 턴을 만들면 Provider 를 열 번 부르게 된다.
	 *
	 * <p><b>한도만큼 세지 않고 한 번에 채운다</b> (이슈 #217). 고정 창이므로(§13-28) 한도만큼
	 * 왕복하는 동안 분 경계를 넘으면 <b>카운터가 1부터 다시 센다</b> — 그러면 막혀야 할 요청이
	 * 통과해 한도 게이트가 아니라 그 뒤의 코드를 만나고, 실패는 <b>CI 가 느린 날에만</b> 나타난다.
	 */
	private void exhaust(String scope, UUID playerRef, int limit) {
		java.time.Duration window = "turn-day".equals(scope) ? RateLimitProperties.DAY
				: RateLimitProperties.MINUTE;
		RateLimitWindows.exhaust(this.redis, this.rateLimiter, scope, playerRef.toString(), limit,
				window);
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

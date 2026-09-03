package com.neowadaeum.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.common.support.RateLimitProperties;
import com.neowadaeum.common.support.RateLimitWindows;
import com.neowadaeum.common.support.RateLimiter;
import com.neowadaeum.common.support.Sha256;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * 이슈 #277 — 인증 없이 열리는 두 조회가 <b>실제 요청에서</b> 한도에 걸린다 (S-8, §13.10, #261).
 *
 * <p><b>축이 하나라는 것을 왕복으로 확인한다.</b> 창을 한 번 소진하면 두 경로가 <b>모두</b>
 * 막혀야 한다 — 한쪽만 막히면 다른 쪽이 우회로다. 무엇으로 세는지는 {@code PublicReadGuardTests}
 * 가 컨테이너 없이 본다.
 */
class PublicReadRateLimitIntegrationTests extends ContainerTestBase {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private RateLimiter rateLimiter;

	@Autowired
	private StringRedisTemplate redis;

	@Autowired
	private RateLimitProperties limits;

	/**
	 * <b>창을 미리 소진한다</b> (이슈 #217). 한도만큼 실제로 왕복하면 고정 창의 분 경계를 넘는
	 * 순간 카운터가 1 부터 다시 세고, 그 실패는 <b>CI 가 느린 날에만</b> 나타난다.
	 *
	 * <p>키는 MockMvc 요청의 주소를 해시한 것이다 — 서버가 세는 것과 같은 값이어야 한다.
	 */
	private void exhaustWindow() {
		RateLimitWindows.exhaust(this.redis, this.rateLimiter, PublicReadGuard.SCOPE,
				Sha256.hex("127.0.0.1"), this.limits.publicReadPerMinutePerIp(),
				RateLimitProperties.MINUTE);
	}

	/** 한도를 넘긴 공개 조회는 {@code 429 RATE_LIMITED} 다. */
	@Test
	void S13_10_public_config_reads_are_rate_limited() throws Exception {
		exhaustWindow();

		MvcResult result = this.mockMvc.perform(get("/api/v1/landing")).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(429);
		assertThat(JSON.readTree(result.getResponse().getContentAsString()).path("error").asString())
				.isEqualTo("RATE_LIMITED");
		assertThat(JSON.readTree(result.getResponse().getContentAsString()).path("details")
				.path("retryAfterSeconds").asLong()).isPositive();
	}

	/** <b>같은 창이다</b> — 랜딩으로 소진한 뒤 약관 메타로 옮겨 가도 통하지 않는다 (이슈 #277). */
	@Test
	void S13_10_the_other_public_read_is_not_a_way_around_the_limit() throws Exception {
		exhaustWindow();

		assertThat(this.mockMvc.perform(get("/api/v1/consents")).andReturn().getResponse().getStatus())
				.isEqualTo(429);
	}

	/** 한도 안에서는 그대로 열린다 — 인증 없이 열린다는 성질이 바뀌지 않았다. */
	@Test
	void S13_10_a_read_within_the_limit_is_still_open() throws Exception {
		assertThat(this.mockMvc.perform(get("/api/v1/landing")).andReturn().getResponse().getStatus())
				.isEqualTo(200);
	}
}

package com.neowadaeum.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.common.support.RateLimitProperties;
import com.neowadaeum.common.support.RateLimitWindows;
import com.neowadaeum.common.support.RateLimiter;
import com.neowadaeum.common.support.Sha256;
import java.util.UUID;
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
 *
 * <p>§13-54(이슈 #306) — 탐색도 인증 밖으로 열렸고 <b>자기 창</b>을 갖는다. 여기서는 그 창이
 * 실제 요청에서 걸리는 것과, <b>두 창이 서로를 소진시키지 않는 것</b>을 함께 본다.
 */
class PublicReadRateLimitIntegrationTests extends ContainerTestBase {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** 시드 작품이다 — 탐색 창이 <b>경로를 가리지 않는다</b>는 것만 보므로 어떤 작품이어도 된다. */
	private static final UUID SEED_STORY = UUID.fromString("11111111-1111-4111-8111-000000000001");

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

	/** 탐색의 창은 별개다 (§13-54) — 소진도 따로 한다. */
	private void exhaustBrowseWindow() {
		RateLimitWindows.exhaust(this.redis, this.rateLimiter, PublicReadGuard.BROWSE_SCOPE,
				Sha256.hex("127.0.0.1"), this.limits.publicBrowsePerMinutePerIp(),
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

	/** 한도를 넘긴 탐색은 {@code 429 RATE_LIMITED} 다 (§13-54, 이슈 #306). */
	@Test
	void S13_54_browsing_is_rate_limited() throws Exception {
		exhaustBrowseWindow();

		MvcResult result = this.mockMvc.perform(get("/api/v1/library")).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(429);
		assertThat(JSON.readTree(result.getResponse().getContentAsString()).path("error").asString())
				.isEqualTo("RATE_LIMITED");
		assertThat(JSON.readTree(result.getResponse().getContentAsString()).path("details")
				.path("retryAfterSeconds").asLong()).isPositive();
	}

	/** 탐색 셋은 <b>한 창</b>이다 — 라이브러리로 소진하면 작품 상세도 막힌다 (§13-54). */
	@Test
	void S13_54_the_story_detail_is_not_a_way_around_the_browse_limit() throws Exception {
		exhaustBrowseWindow();

		assertThat(this.mockMvc.perform(get("/api/v1/stories/{storyId}", SEED_STORY)).andReturn()
				.getResponse().getStatus()).isEqualTo(429);
	}

	/**
	 * <b>탐색이 가입 경로의 창을 소진하지 않는다</b> (§13-54, 이슈 #306).
	 *
	 * <p>둘을 한 창에 넣었다면 <b>둘러보던 사람이 약관을 읽지 못한다</b> — {@code /consents} 가
	 * 막히면 판본을 읽을 방법이 없고 가입 요청이 성립하지 않는다.
	 *
	 * <p>가입 경로 쪽 창이 살아 있다는 것은 {@code /landing} 의 {@code 200} 으로 본다 — 둘이
	 * <b>한 창</b>이라는 것은 #277 의 테스트가 이미 못박았다. {@code /consents} 는 <b>한도에
	 * 막히지 않았다는 것</b>만 확인한다: 그 응답의 성공 여부는 약관 설정이 정하는 별개의 사실이고
	 * (§13-51), 여기서 그것까지 요구하면 <b>이 테스트가 다른 이유로 깨진다.</b>
	 */
	@Test
	void S13_54_browsing_does_not_close_the_signup_path() throws Exception {
		exhaustBrowseWindow();

		assertThat(this.mockMvc.perform(get("/api/v1/landing")).andReturn().getResponse().getStatus())
				.isEqualTo(200);
		assertThat(this.mockMvc.perform(get("/api/v1/consents")).andReturn().getResponse().getStatus())
				.isNotEqualTo(429);
	}

	/** 반대 방향도 같다 — 설정 조회를 다 써도 둘러보기는 열려 있다 (§13-54). */
	@Test
	void S13_54_the_config_read_limit_does_not_close_browsing() throws Exception {
		exhaustWindow();

		assertThat(this.mockMvc.perform(get("/api/v1/library")).andReturn().getResponse().getStatus())
				.isEqualTo(200);
	}
}

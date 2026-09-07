package com.neowadaeum.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.common.support.RateLimitProperties;
import com.neowadaeum.common.support.RateLimitWindows;
import com.neowadaeum.common.support.RateLimiter;
import com.neowadaeum.common.support.Sha256;
import com.neowadaeum.identity.auth.LoginNonceStore;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * §13-87 (#424) — <b>로그인 nonce 가 실제 저장소 위에서 한 번만 통과한다.</b>
 *
 * <p>단위 테스트는 소비 결과를 <b>가정</b>한다. 여기서 보는 것은 그 가정이 성립하는가다 — 원자적
 * 삭제 · 짧은 수명 · 인증 없이 열린 경로 · IP 기준 한도는 전부 <b>배선이 되어 있어야</b> 성립한다.
 *
 * <p><b>다른 IP 로 잰다.</b> 인증 경로 셋이 같은 창을 쓰므로(S-8), 기본 주소로 창을 소진하면
 * 같은 컨텍스트의 다른 인증 테스트가 <b>한도에 걸린 429 를 받는다</b> — 그리고 그 실패는 이
 * 파일을 가리키지 않는다.
 */
class LoginNonceIntegrationTests extends ContainerTestBase {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** 문서용 주소 대역(TEST-NET-3). 실재하는 호스트를 적지 않는다 (S-11). */
	private static final String OWN_ADDRESS = "203.0.113.7";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private LoginNonceStore nonces;

	@Autowired
	private RateLimiter rateLimiter;

	@Autowired
	private RateLimitProperties limits;

	@Autowired
	private StringRedisTemplate redis;

	/** 발급 경로가 값을 돌려준다 — <b>인증 없이</b>. 닫혀 있으면 아무도 로그인을 시작할 수 없다. */
	@Test
	void S13_87_the_nonce_endpoint_issues_a_value_without_authentication() throws Exception {
		MvcResult result = this.mockMvc.perform(issueNonce()).andReturn();

		assertThat(result.getResponse().getStatus())
				.as("401 이면 보안 체인이 막은 것이다 — 이 경로는 로그인보다 앞이다")
				.isEqualTo(200);
		assertThat(nonceOf(result)).isNotBlank();
	}

	/** 발급된 값은 <b>실제로 소비된다</b> — 발급과 소비가 다른 키를 보면 로그인이 전부 막힌다. */
	@Test
	void S13_87_an_issued_nonce_is_the_one_the_login_path_consumes() throws Exception {
		String issued = nonceOf(this.mockMvc.perform(issueNonce()).andReturn());

		assertThat(this.nonces.consume(issued)).isTrue();
	}

	/**
	 * <b>두 번째는 막힌다</b> (§13-87).
	 *
	 * <p>1회성이 없으면 nonce 는 <b>토큰과 함께 재생되는 값</b>이 되어 아무것도 막지 못한다.
	 */
	@Test
	void S13_87_the_same_nonce_never_passes_twice() {
		String issued = this.nonces.issue().value();

		assertThat(this.nonces.consume(issued)).isTrue();
		assertThat(this.nonces.consume(issued)).isFalse();
	}

	/** 발급된 적 없는 값 · 빈 값 · 없는 값은 전부 막힌다 — 만료된 것과 <b>구분되지 않는다</b> (S-6). */
	@Test
	void SEC6_an_unissued_or_absent_nonce_is_rejected_the_same_way() {
		assertThat(this.nonces.consume("never-issued")).isFalse();
		assertThat(this.nonces.consume("")).isFalse();
		assertThat(this.nonces.consume(null)).isFalse();
	}

	/**
	 * <b>동시에 들어와도 하나만 통과한다</b> (§13-87).
	 *
	 * <p>조회와 삭제가 갈라지면 <b>둘 다 조회에 성공한 뒤 둘 다 통과</b>한다 — 막으려던 재생이
	 * 정확히 그 창으로 들어온다. 원자적 삭제만이 승자를 하나로 만든다.
	 */
	@Test
	void S13_87_only_one_of_two_concurrent_consumers_wins() throws Exception {
		String issued = this.nonces.issue().value();
		Callable<Boolean> consume = () -> this.nonces.consume(issued);

		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			List<Future<Boolean>> attempts = pool.invokeAll(List.of(consume, consume));

			assertThat(List.of(attempts.get(0).get(), attempts.get(1).get()))
					.as("승자는 정확히 하나여야 한다")
					.containsExactlyInAnyOrder(true, false);
		}
	}

	/**
	 * <b>S-8 — 인증 없이 열리고 서버에 상태를 만드는 경로에는 한도가 걸린다</b> (§13-87).
	 *
	 * <p>없으면 누구나 저장소를 채울 수 있고, 그것은 로그인 실패가 아니라 서비스 전체의 문제다.
	 * 인증 경로가 쓰는 <b>같은 창</b>을 소진시켜 확인한다 — 창이 갈라져 있으면 여기서 드러난다.
	 */
	@Test
	void SEC8_the_nonce_path_is_limited_by_ip() throws Exception {
		RateLimitWindows.exhaust(this.redis, this.rateLimiter, "auth-ip", Sha256.hex(OWN_ADDRESS),
				this.limits.authPerMinutePerIp(), RateLimitProperties.MINUTE);

		MvcResult result = this.mockMvc.perform(issueNonce()).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(429);
		assertThat(JSON.readTree(result.getResponse().getContentAsString()).path("error").asString())
				.isEqualTo("RATE_LIMITED");
	}

	private static MockHttpServletRequestBuilder issueNonce() {
		return post("/api/v1/auth/nonce").with(request -> {
			request.setRemoteAddr(OWN_ADDRESS);
			return request;
		});
	}

	private static String nonceOf(MvcResult result) throws Exception {
		return JSON.readTree(result.getResponse().getContentAsString()).path("nonce").asString();
	}
}

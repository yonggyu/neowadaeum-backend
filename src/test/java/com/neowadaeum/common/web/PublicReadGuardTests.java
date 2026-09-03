package com.neowadaeum.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.common.error.GlobalExceptionHandler;
import com.neowadaeum.common.support.RateLimitProperties;
import com.neowadaeum.common.support.RateLimiter;
import com.neowadaeum.common.support.Sha256;
import com.neowadaeum.identity.api.ConsentTermsController;
import com.neowadaeum.identity.api.ConsentTermsService;
import com.neowadaeum.play.api.LandingController;
import com.neowadaeum.play.api.LandingService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 이슈 #277 — <b>인증 없이 열리는 설정 조회의 호출 한도</b> (S-8, §13.10, #261).
 *
 * <p>컨테이너를 띄우지 않는다 (ADR-0001). Redis 로 실제 창을 소진하는 것은 {@code RateLimiter}
 * 의 몫이고 {@code RateLimitIntegrationTests} 가 이미 본다 — 여기서 확인하는 것은 <b>이 두 경로가
 * 무엇으로 세는가</b>와 <b>둘이 같은 창을 쓰는가</b>다.
 */
class PublicReadGuardTests {

	/** 문서용 주소다 (RFC 5737). 실제 대역이 아니다 (S-11). */
	private static final String ADDRESS = "203.0.113.7";

	private final RateLimiter rateLimiter = mock(RateLimiter.class);

	private final RateLimitProperties limits = RateLimitProperties.defaults();

	private final PublicReadGuard guard = new PublicReadGuard(this.rateLimiter, this.limits);

	private final ArgumentCaptor<String> scopes = ArgumentCaptor.forClass(String.class);

	private final ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);

	/**
	 * <b>한도를 넘기면 {@code RATE_LIMITED} 다</b> (이슈 #277, S-8).
	 *
	 * <p>값이 {@code service_config} 에서 오고 <b>캐시가 없으므로</b>(그 이유는
	 * {@code CatalogServiceConfigQuery} 에 있다) 요청 수가 곧 DB 읽기 수다 — 토큰 없이 그것을
	 * 무제한으로 만들 수 있어서는 안 된다.
	 */
	@Test
	void S13_10_public_config_reads_are_rate_limited() {
		given(this.rateLimiter.tryAcquire(anyString(), anyString(), anyInt(), any())).willReturn(false);
		given(this.rateLimiter.retryAfterSeconds(RateLimitProperties.MINUTE)).willReturn(42L);

		assertThatExceptionOfType(ApiException.class)
				.isThrownBy(() -> this.guard.requireWithinIpLimit(requestFrom(ADDRESS)))
				.satisfies(thrown -> {
					assertThat(thrown.errorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
					// 언제 다시 올지 알려주지 않으면 클라이언트가 즉시 재시도한다.
					assertThat(thrown.details()).containsEntry("retryAfterSeconds", 42L);
				});
	}

	/** 한도 안에서는 통과한다 — 막는 것이 목적이 아니라 폭주를 막는 것이 목적이다. */
	@Test
	void S13_10_a_read_within_the_limit_passes() {
		allow();

		this.guard.requireWithinIpLimit(requestFrom(ADDRESS));

		verify(this.rateLimiter).tryAcquire(anyString(), anyString(),
				eq(this.limits.publicReadPerMinutePerIp()), eq(RateLimitProperties.MINUTE));
	}

	/**
	 * <b>IP 원문이 아니라 해시로 센다</b> (§12).
	 *
	 * <p>키에 원문을 넣으면 <b>Redis 가 접속자 목록이 된다.</b> "해시를 쓴다"만 단언하면 원문이
	 * 함께 실려도 통과하므로 <b>원문이 아니라는 것</b>을 같이 못박는다 (S-11).
	 */
	@Test
	void S12_the_public_read_is_counted_by_an_ip_hash_not_the_address() {
		allow();

		this.guard.requireWithinIpLimit(requestFrom(ADDRESS));

		captureCalls(1);
		assertThat(this.keys.getValue()).isEqualTo(Sha256.hex(ADDRESS)).isNotEqualTo(ADDRESS);
	}

	/** 회선이 다르면 창도 다르다 — 한 회선이 다른 회선을 막지 못한다. */
	@Test
	void SEC8_a_different_address_gets_a_different_window() {
		allow();

		this.guard.requireWithinIpLimit(requestFrom(ADDRESS));
		this.guard.requireWithinIpLimit(requestFrom("203.0.113.8"));

		captureCalls(2);
		assertThat(this.keys.getAllValues()).doesNotHaveDuplicates();
	}

	/**
	 * <b>두 경로가 같은 창을 쓴다</b> (이슈 #277).
	 *
	 * <p>이슈가 둘을 함께 정한 이유가 이것이다 — 각자 세면 한쪽을 다 쓴 뒤 다른 쪽으로 옮겨
	 * 가면 되고, 그러면 <b>사실상 한도가 두 배</b>가 된다. 두 컨트롤러를 실제로 태워 확인한다:
	 * 같아야 하는 것은 선언이 아니라 <b>실제로 넘어가는 축</b>이다.
	 */
	@Test
	void S13_10_both_public_reads_share_one_window() throws Exception {
		allow();
		MockMvc mvc = MockMvcBuilders
				.standaloneSetup(new LandingController(mock(LandingService.class), this.guard),
						new ConsentTermsController(mock(ConsentTermsService.class), this.guard))
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();

		mvc.perform(get("/api/v1/landing"));
		mvc.perform(get("/api/v1/consents"));

		captureCalls(2);
		assertThat(this.scopes.getAllValues().get(0)).isEqualTo(this.scopes.getAllValues().get(1));
		assertThat(this.keys.getAllValues().get(0)).isEqualTo(this.keys.getAllValues().get(1));
	}

	// ── 보조 ────────────────────────────────────────────────

	private void allow() {
		given(this.rateLimiter.tryAcquire(anyString(), anyString(), anyInt(), any())).willReturn(true);
	}

	/** 한도를 몇 번 셌는지와 함께 <b>무엇으로</b> 셌는지를 잡는다. */
	private void captureCalls(int expected) {
		verify(this.rateLimiter, times(expected)).tryAcquire(this.scopes.capture(),
				this.keys.capture(), anyInt(), any());
	}

	private static MockHttpServletRequest requestFrom(String address) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr(address);
		return request;
	}
}

package com.neowadaeum.identity.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.common.error.GlobalExceptionHandler;
import com.neowadaeum.common.support.RateLimitProperties;
import com.neowadaeum.common.support.RateLimiter;
import com.neowadaeum.identity.auth.AuthTokens;
import com.neowadaeum.identity.auth.JwtProperties;
import com.neowadaeum.identity.auth.OAuthLoginService;
import com.neowadaeum.identity.auth.RefreshCookieProperties;
import com.neowadaeum.identity.auth.RefreshTokenCookie;
import com.neowadaeum.identity.domain.OauthProvider;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * B-12(2/3) — 인증 API 의 <b>계약 표면</b> (§13.1, §13-22).
 *
 * <p>컨텍스트를 띄우지 않는다 (ADR-0001). 여기서 보는 것은 경로·본문·에러 매핑이며,
 * 보안 체인을 지나는 실제 왕복은 3/3 이다.
 */
class AuthControllerTests {

	private static final AuthTokens ISSUED = new AuthTokens("access-1", "refresh-1", 1800);

	private final OAuthLoginService login = mock(OAuthLoginService.class);

	/** 한도는 여기서 검사 대상이 아니다 — 늘 통과시킨다 (B-38 은 통합 테스트가 본다). */
	private final RateLimiter alwaysAllows = mock(RateLimiter.class, invocation ->
			invocation.getMethod().getReturnType() == boolean.class ? Boolean.TRUE : 0L);

	/**
	 * 실제 쿠키 구현을 쓴다 (ADR-0008). 목으로 바꾸면 <b>이 테스트가 지키려는 속성이 사라진다</b> —
	 * 검사 대상이 {@code HttpOnly} · {@code Path} · {@code SameSite} 이기 때문이다.
	 *
	 * <p>{@code secure} 는 {@code false} 다 — MockMvc 는 평문 HTTP 다. 그 값이 <b>설정에서
	 * 오고 기본값이 없다</b>는 것은 {@code RefreshCookieProperties} 의 {@code @NotNull} 이
	 * 부팅에서 지킨다 (§7.3).
	 */
	private final RefreshTokenCookie refreshCookie = new RefreshTokenCookie(
			new JwtProperties("test-only-signing-material-not-a-real-secret", Duration.ofMinutes(30),
					Duration.ofDays(30), Duration.ofMinutes(15)),
			new RefreshCookieProperties(false));

	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AuthController(this.login,
					this.alwaysAllows, RateLimitProperties.defaults(), this.refreshCookie))
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();

	/** §13-22 — 계약이 정한 응답 그대로. {@code playerRef} 는 없다. */
	@Test
	void S13_22_login_returns_the_contract_token_response() throws Exception {
		given(this.login.login(eq(OauthProvider.GOOGLE), eq("id-token"), any(), any())).willReturn(ISSUED);

		this.mvc.perform(post("/api/v1/auth/oauth/google")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"idToken\":\"id-token\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").value("access-1"))
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.expiresIn").value(1800))
				.andExpect(jsonPath("$.playerRef").doesNotExist());
	}

	/**
	 * ADR-0008 — <b>리프레시 토큰이 본문에 없다.</b>
	 *
	 * <p>값을 필드 이름으로만 찾지 않고 <b>본문 전체에서</b> 찾는다. 이름이 바뀌어도 값이 새면
	 * 잡혀야 하기 때문이다 — "있어야 할 것"만 단언하면 값이 새어도 통과한다 (S-3).
	 */
	@Test
	void Issue278_the_refresh_token_never_appears_in_the_login_body() throws Exception {
		given(this.login.login(eq(OauthProvider.GOOGLE), eq("id-token"), any(), any())).willReturn(ISSUED);

		String body = this.mvc.perform(post("/api/v1/auth/oauth/google")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"idToken\":\"id-token\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.refreshToken").doesNotExist())
				.andReturn().getResponse().getContentAsString();

		Assertions.assertThat(body).doesNotContain(ISSUED.refreshToken());
	}

	/**
	 * ADR-0008 — 로그인이 굽는 쿠키의 속성.
	 *
	 * <p><b>{@code Path} 가 이 결정의 핵심이다.</b> 넓으면 리프레시 토큰이 모든 API 요청에
	 * 자동으로 실리고, CSRF 를 감당해야 하는 경로가 하나에서 전부로 는다.
	 */
	@Test
	void Issue278_login_bakes_the_refresh_token_into_a_path_scoped_cookie() throws Exception {
		given(this.login.login(eq(OauthProvider.GOOGLE), eq("id-token"), any(), any())).willReturn(ISSUED);

		String setCookie = this.mvc.perform(post("/api/v1/auth/oauth/google")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"idToken\":\"id-token\"}"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);

		Assertions.assertThat(setCookie)
				.as("리프레시 토큰은 이 헤더 하나로만 나간다 (ADR-0008)")
				.contains(ISSUED.refreshToken())
				.contains("HttpOnly")
				.contains("Path=/api/v1/auth/refresh")
				.contains("SameSite=Strict")
				.contains("Max-Age=2592000");
	}

	/** 계약의 enum 이 {@code [google]} 이다. 목록에 없는 값은 보낼 수 없는 값이다. */
	@Test
	void S13_11_an_unknown_provider_is_a_400() throws Exception {
		this.mvc.perform(post("/api/v1/auth/oauth/kakao")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"idToken\":\"id-token\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

		verify(this.login, never()).login(any(), any(), any(), any());
	}

	/** 검증 실패는 401 이고, 어느 검사에서 걸렸는지 알려주지 않는다 (S-6). */
	@Test
	void S13_1_a_rejected_id_token_is_a_401() throws Exception {
		given(this.login.login(eq(OauthProvider.GOOGLE), any(), any(), any()))
				.willThrow(new ApiException(ErrorCode.UNAUTHENTICATED));

		this.mvc.perform(post("/api/v1/auth/oauth/google")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"idToken\":\"forged\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
	}

	/** 재발급도 같은 응답 형태다. <b>자격 증명은 쿠키 하나이며 본문이 없다</b> (ADR-0008). */
	@Test
	void S13_1_refresh_returns_a_new_access_token() throws Exception {
		given(this.login.refresh("refresh-0")).willReturn(ISSUED);

		this.mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("nwd_rt", "refresh-0")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").value("access-1"))
				.andExpect(jsonPath("$.refreshToken").doesNotExist());
	}

	/**
	 * ADR-0008 — <b>본문으로는 통하지 않는다.</b>
	 *
	 * <p>자격 증명을 받는 자리가 둘이면 {@code HttpOnly} 가 주는 보장이 문장으로만 남는다.
	 * 쿠키 없이 본문만 보내면 <b>쿠키가 아예 없는 것과 같은 응답</b>이어야 한다.
	 */
	@Test
	void Issue278_a_refresh_token_in_the_body_is_not_a_credential() throws Exception {
		this.mvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"refreshToken\":\"refresh-0\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));

		verify(this.login, never()).refresh(any());
	}

	/** 쿠키가 없으면 401 이다 — 토큰이 틀린 것과 같은 응답이다 (S-6). */
	@Test
	void Issue278_a_missing_cookie_is_a_401() throws Exception {
		this.mvc.perform(post("/api/v1/auth/refresh"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));

		verify(this.login, never()).refresh(any());
	}

	/** 재발급도 새 쿠키를 굽는다. 굽지 않으면 수명이 첫 로그인 시점에 고정된다. */
	@Test
	void Issue278_refresh_rebakes_the_cookie() throws Exception {
		given(this.login.refresh("refresh-0")).willReturn(ISSUED);

		String setCookie = this.mvc.perform(post("/api/v1/auth/refresh")
						.cookie(new Cookie("nwd_rt", "refresh-0")))
				.andExpect(status().isOk())
				.andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);

		Assertions.assertThat(setCookie).contains(ISSUED.refreshToken())
				.contains("Path=/api/v1/auth/refresh");
	}
}

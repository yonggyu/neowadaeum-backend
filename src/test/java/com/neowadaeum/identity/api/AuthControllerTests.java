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
import com.neowadaeum.identity.auth.AuthTokens;
import com.neowadaeum.identity.auth.OAuthLoginService;
import com.neowadaeum.identity.domain.OauthProvider;
import org.junit.jupiter.api.Test;
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

	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AuthController(this.login))
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
				.andExpect(jsonPath("$.refreshToken").value("refresh-1"))
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.expiresIn").value(1800))
				.andExpect(jsonPath("$.playerRef").doesNotExist());
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

	/** 재발급도 같은 응답 형태다. */
	@Test
	void S13_1_refresh_returns_a_new_pair() throws Exception {
		given(this.login.refresh("refresh-0")).willReturn(ISSUED);

		this.mvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"refreshToken\":\"refresh-0\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").value("access-1"));
	}
}

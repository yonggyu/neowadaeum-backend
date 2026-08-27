package com.neowadaeum.identity.api;

import com.neowadaeum.identity.auth.AuthTokens;

/**
 * 토큰 응답 (§13-22 의 {@code TokenResponse}).
 *
 * <p><b>{@code playerRef} 를 담지 않는다.</b> 계약이 그렇게 정했다 — 클라이언트가 알 필요가 없고,
 * 알 필요 없는 값을 주면 그 값이 로그·에러 리포트·분석 도구로 퍼진다.
 */
public record TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresIn) {

	static TokenResponse of(AuthTokens tokens) {
		return new TokenResponse(tokens.accessToken(), tokens.refreshToken(), AuthTokens.TOKEN_TYPE,
				tokens.expiresIn());
	}
}

package com.neowadaeum.identity.api;

import com.neowadaeum.identity.auth.AuthTokens;

/**
 * 토큰 응답 (§13-22 의 {@code TokenResponse}).
 *
 * <p><b>{@code playerRef} 를 담지 않는다.</b> 계약이 그렇게 정했다 — 클라이언트가 알 필요가 없고,
 * 알 필요 없는 값을 주면 그 값이 로그·에러 리포트·분석 도구로 퍼진다.
 *
 * <p><b>{@code refreshToken} 도 담지 않는다</b> (ADR-0008, #278). 리프레시 토큰은 재발급 경로
 * 전용 {@code HttpOnly} 쿠키로만 간다. 본문으로도 주면 <b>JS 가 읽었다는 뜻</b>이고, 그 값이
 * {@code localStorage} 로 가는 것을 서버는 막을 방법이 없다 — {@code HttpOnly} 가 주는 보장이
 * 문장으로만 남는 지점이 거기다.
 */
public record TokenResponse(String accessToken, String tokenType, long expiresIn) {

	static TokenResponse of(AuthTokens tokens) {
		return new TokenResponse(tokens.accessToken(), AuthTokens.TOKEN_TYPE, tokens.expiresIn());
	}
}

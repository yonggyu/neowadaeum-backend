package com.neowadaeum.identity.auth;

/**
 * 발급된 토큰 한 벌 (§13-22 의 {@code TokenResponse}).
 *
 * <p><b>{@code playerRef} 를 담지 않는다.</b> 계약이 그렇게 정했고 이유는 단순하다 — 클라이언트가
 * 알 필요가 없다. 알 필요 없는 값을 주면 그 값이 로그·에러 리포트·분석 도구로 퍼진다.
 *
 * @param accessToken  보호 API 에 {@code Authorization: Bearer} 로 보낸다
 * @param refreshToken 액세스 토큰 재발급 전용. 보호 API 에는 통하지 않는다
 * @param expiresIn    액세스 토큰 만료까지 남은 초
 */
public record AuthTokens(String accessToken, String refreshToken, long expiresIn) {

	/** 계약이 고정한 값이다 ({@code TokenResponse.tokenType} 의 {@code const}). */
	public static final String TOKEN_TYPE = "Bearer";
}

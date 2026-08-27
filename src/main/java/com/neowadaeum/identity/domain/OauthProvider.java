package com.neowadaeum.identity.domain;

/**
 * 소셜 로그인 provider (§2.2).
 *
 * <p><b>MVP 에서 실제로 쓰이는 것은 {@link #GOOGLE} 하나다</b> (§13-11 채택안). {@link #APPLE} 은
 * 원문이 규정한 값이라 함께 두되 어댑터는 B-12 범위 밖이다 — 값이 존재하는 것과 로그인 경로가
 * 열려 있는 것은 다르다.
 */
public enum OauthProvider {

	GOOGLE,

	APPLE
}

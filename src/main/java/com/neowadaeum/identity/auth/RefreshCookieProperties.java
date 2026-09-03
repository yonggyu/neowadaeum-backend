package com.neowadaeum.identity.auth;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 리프레시 쿠키의 배포 의존 속성 (ADR-0008, #278).
 *
 * <p><b>여기 있는 것은 {@code Secure} 하나다.</b> 이름 · {@code Path} · {@code HttpOnly} ·
 * {@code SameSite} 는 {@link RefreshTokenCookie} 가 코드로 고정한다 — 배포마다 달라질 값이
 * 아니라 <b>이 설계가 성립하기 위한 조건</b>이기 때문이다 (ADR-0008).
 *
 * <p><b>기본값을 두지 않는다</b> (§7.3 — {@code ${VAR:값}} 금지). 값이 없으면 부팅이 실패한다.
 * 기본을 {@code false} 로 두면 <b>설정을 빠뜨린 운영 배포가 평문으로 리프레시 토큰을 실어
 * 나른다</b> — 그 실패는 서버 로그에 남지 않는다. 기본을 {@code true} 로 두면 반대로 로컬
 * 개발이 원인 없이 로그인되지 않는다.
 *
 * <p><b>프로파일로 갈라 자동으로 정하지 않는다.</b> {@code dev} 프로파일이 보안 속성을 스스로
 * 낮추는 구조가 정확히 #34 가 지운 것이다.
 *
 * @param secure HTTPS 로 서빙되는 환경인가. 배포된 모든 환경은 {@code true} 다
 */
@Validated
@ConfigurationProperties("auth.refresh-cookie")
public record RefreshCookieProperties(@NotNull Boolean secure) {
}

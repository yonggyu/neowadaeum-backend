/**
 * <b>관리자 경로의 문</b> (S-4, R14.6).
 *
 * <p>{@code @NamedInterface} 로 <b>이 패키지만</b> 노출한다. {@code identity.auth} 에는 서명 키와
 * 토큰 발급이 있고, 그것까지 열면 <b>다른 모듈이 토큰을 만들 수 있게 된다</b> — 인증의 근거가
 * 인증을 쓰는 쪽으로 흘러가면 안 된다.
 *
 * <p>위반은 {@code ApplicationModules.verify()}가 빌드에서 잡는다.
 */
@NamedInterface("access")
package com.neowadaeum.identity.access;

import org.springframework.modulith.NamedInterface;

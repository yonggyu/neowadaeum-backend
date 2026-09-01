/**
 * <b>{@code ai} 모듈이 관리자 화면에 내주는 것</b> (§14 Debug, R12.3).
 *
 * <p>{@code @NamedInterface} 로 <b>이 패키지만</b> 노출한다. {@code ai.log} 는 엔티티와
 * 리포지토리를 들고 있고, 그것까지 열면 <b>다른 모듈이 원문 표를 직접 읽을 수 있게 된다</b> —
 * 그러면 열람 기록을 우회하는 길이 생긴다 (S-5).
 *
 * <p>위반은 {@code ApplicationModules.verify()}가 빌드에서 잡는다.
 */
@NamedInterface("debug")
package com.neowadaeum.ai.debug;

import org.springframework.modulith.NamedInterface;

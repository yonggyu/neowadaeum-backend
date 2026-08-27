/**
 * <b>{@code play} 모듈이 관리자 화면에 내주는 것</b> (§14 Debug).
 *
 * <p>{@code @NamedInterface} 로 <b>이 패키지만</b> 노출한다. {@code play.repository} 와
 * {@code play.domain} 을 열면 다른 모듈이 세션과 턴을 직접 고칠 수 있게 된다 — 상태 전이는
 * 이 모듈의 규칙이며 밖에서 건드릴 것이 아니다.
 *
 * <p>위반은 {@code ApplicationModules.verify()}가 빌드에서 잡는다.
 */
@NamedInterface("debug")
package com.neowadaeum.play.debug;

import org.springframework.modulith.NamedInterface;

/**
 * UGC 드래프트·검수·신고·블록리스트.
 *
 * <p>§5.2 패키지 구조 / §5.4 모듈 간 의존 규칙. 허용 의존은 이 애노테이션이 유일한 진실의 원천이며,
 * 위반은 {@code ApplicationModules.verify()}가 빌드에서 잡는다.
 */
@ApplicationModule(allowedDependencies = { "common", "catalog", "safety" })
package com.neowadaeum.authoring;

import org.springframework.modulith.ApplicationModule;

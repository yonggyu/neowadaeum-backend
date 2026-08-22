/**
 * L0~L3 판정기, 블록리스트, 카테고리 정책.
 *
 * <p>§5.2 패키지 구조 / §5.4 모듈 간 의존 규칙. 허용 의존은 이 애노테이션이 유일한 진실의 원천이며,
 * 위반은 {@code ApplicationModules.verify()}가 빌드에서 잡는다.
 */
@ApplicationModule(allowedDependencies = "common")
package com.neowadaeum.safety;

import org.springframework.modulith.ApplicationModule;

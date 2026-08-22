/**
 * AI 파이프라인. 도메인 엔티티를 알지 못하며 순수 DTO만 주고받는다.
 *
 * <p>§5.2 패키지 구조 / §5.4 모듈 간 의존 규칙. 허용 의존은 이 애노테이션이 유일한 진실의 원천이며,
 * 위반은 {@code ApplicationModules.verify()}가 빌드에서 잡는다.
 */
@ApplicationModule(allowedDependencies = "common")
package com.neowadaeum.ai;

import org.springframework.modulith.ApplicationModule;

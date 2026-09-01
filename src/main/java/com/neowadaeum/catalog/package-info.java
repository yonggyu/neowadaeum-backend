/**
 * 작품·버전·챕터·엔딩·캐릭터·장르.
 *
 * <p>§5.2 패키지 구조 / §5.4 모듈 간 의존 규칙. 허용 의존은 이 애노테이션이 유일한 진실의 원천이며,
 * 위반은 {@code ApplicationModules.verify()}가 빌드에서 잡는다.
 */
@ApplicationModule(allowedDependencies = "common")
package com.neowadaeum.catalog;

import org.springframework.modulith.ApplicationModule;

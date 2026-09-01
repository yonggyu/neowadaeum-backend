/**
 * 공통 응답·에러·시간·ID·정규화 유틸. 전 모듈이 참조하는 공용 기반이다.
 *
 * <p>§5.2 패키지 구조 / §5.4 모듈 간 의존 규칙. 허용 의존은 이 애노테이션이 유일한 진실의 원천이며,
 * 위반은 {@code ApplicationModules.verify()}가 빌드에서 잡는다.
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
package com.neowadaeum.common;

import org.springframework.modulith.ApplicationModule;

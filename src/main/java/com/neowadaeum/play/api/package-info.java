/**
 * 플레이 HTTP 진입점 (§4.2, §4.3 의 1·2·12 단계).
 *
 * <p>play 모듈의 내부 패키지다. 다른 모듈이 직접 참조하지 않는다 (§5.4).
 *
 * <p><b>Controller 는 요청 검증과 DTO 변환만 한다</b> (web-api 규칙). 파이프라인은
 * {@code play/orchestrator} 가 갖는다.
 */
package com.neowadaeum.play.api;

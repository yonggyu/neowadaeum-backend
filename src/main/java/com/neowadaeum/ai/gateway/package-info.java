/**
 * AiGateway, PayloadWhitelistValidator, FallbackChain (B-18, B-19, B-23)
 *
 * <p>ai 모듈의 내부 패키지다. 다른 모듈이 직접 참조하지 않는다 (§5.4) — 호출자가 보는 것은
 * {@code ai :: provider} 의 {@code StoryProvider} 하나이며, 게이트웨이는 그 seam 을 구현해
 * <b>경계를 바꾸지 않고</b> 앞단에 선다 (ADR-0005).
 */
package com.neowadaeum.ai.gateway;

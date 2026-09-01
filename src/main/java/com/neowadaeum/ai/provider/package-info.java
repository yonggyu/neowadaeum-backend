/**
 * StoryProvider 인터페이스와 벤더별 어댑터 (B-18, B-22, B-23)
 *
 * <p><b>이 패키지는 ai 모듈의 API 다</b> — {@code @NamedInterface} 로 명시적으로 노출한다 (ADR-0005).
 * §4.3 파이프라인이 Provider 를 부르므로 {@code play} 가 이 seam 을 참조해야 한다. 노출 범위는
 * <b>이 패키지 하나</b>이며 벤더 어댑터({@code anthropic} · {@code openai} · {@code ollama})는
 * 더 깊은 패키지라 그대로 내부다 — 그래서 I-14(Provider 선택은 관리자 전용)가 유지된다.
 *
 * <p>나머지 ai 하위 패키지({@code gateway} · {@code prompt} · {@code schema} · {@code log})는
 * 내부다. 다른 모듈이 참조하지 않는다 (§5.4).
 */
@NamedInterface("provider")
package com.neowadaeum.ai.provider;

import org.springframework.modulith.NamedInterface;

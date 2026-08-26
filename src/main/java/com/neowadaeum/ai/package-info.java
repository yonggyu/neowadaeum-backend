/**
 * AI 파이프라인. 도메인 엔티티를 알지 못하며 순수 DTO만 주고받는다.
 *
 * <p>§5.2 패키지 구조 / §5.4 모듈 간 의존 규칙. 허용 의존은 이 애노테이션이 유일한 진실의 원천이며,
 * 위반은 {@code ApplicationModules.verify()}가 빌드에서 잡는다.
 *
 * <p><b>{@code play :: port} 는 ADR-0006 으로 추가됐다.</b> 이 모듈은 {@code play} 가 소유한 턴 생성
 * 계약({@link com.neowadaeum.play.port.TurnGenerationPort})을 <b>구현하는 쪽</b>이다 — 계약의 모양을
 * 정하는 것은 그것을 저장하고 응답하는 {@code play} 이기 때문이다.
 *
 * <p><b>이것이 도메인 엔티티 참조가 되지는 않는다.</b> 열린 것은 {@code play} 의 계약 패키지 하나이며
 * 거기에는 순수 DTO 와 인터페이스만 있다 — {@code play} 의 엔티티 · Repository · 서비스는 여전히
 * 닫혀 있다 (I-3 의 구조적 보장은 그대로다).
 *
 * <p><b>방향은 단방향이다.</b> {@code play} 는 {@code ai} 를 참조하지 않는다 (ADR-0006).
 */
@ApplicationModule(allowedDependencies = { "common", "play :: port" })
package com.neowadaeum.ai;

import org.springframework.modulith.ApplicationModule;

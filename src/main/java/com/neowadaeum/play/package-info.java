/**
 * 세션·턴·히스토리.
 *
 * <p>§5.2 패키지 구조 / §5.4 모듈 간 의존 규칙. 허용 의존은 이 애노테이션이 유일한 진실의 원천이며,
 * 위반은 {@code ApplicationModules.verify()}가 빌드에서 잡는다.
 *
 * <p><b>허용 대상이 모듈 전체가 아니라 명명 인터페이스 하나씩이다.</b> {@code catalog :: query} ·
 * {@code ai :: provider} · {@code safety :: l2} — 각 모듈이 계약으로 노출한 패키지만 참조할 수 있고,
 * 그 밖의 내부 패키지는 여전히 닫혀 있다. §5.4 의 "모듈 간 호출은 파사드로만"이 선언으로 표현된 것이다.
 *
 * <p><b>{@code ai} · {@code safety} 는 ADR-0005 로 추가됐다.</b> §4.3 파이프라인이 Provider 와 L2 를
 * 부르고, §5.2 가 그 오케스트레이터를 이 모듈에 두기 때문이다. <b>방향은 단방향이다</b> —
 * 두 모듈은 {@code play} 의 도메인 엔티티를 알지 못하므로 순환이 생기지 않는다. 나중에 분리가
 * 필요해지면 {@code common/spi} 의 Port 로 전환한다 (ADR-0005 되돌리기).
 */
@ApplicationModule(allowedDependencies = { "common", "catalog :: query", "ai :: provider", "safety :: l2" })
package com.neowadaeum.play;

import org.springframework.modulith.ApplicationModule;

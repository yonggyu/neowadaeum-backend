/**
 * L0~L3 판정기, 카테고리 정책.
 *
 * <p>§5.2 패키지 구조 / §5.4 모듈 간 의존 규칙. 허용 의존은 이 애노테이션이 유일한 진실의 원천이며,
 * 위반은 {@code ApplicationModules.verify()}가 빌드에서 잡는다.
 *
 * <p><b>블록리스트는 이 모듈이 소유하지 않는다</b>(ADR-0002). {@code blocklist_entry} 는 authoring 소유이며
 * catalog 스키마에 있다. safety 는 {@code common/spi} 의 조회 인터페이스를 주입받아 <b>읽기만</b> 한다.
 * 방향이 요점이다 — {@code safety → authoring} 이 아니라 {@code authoring → common/spi ← safety} 다.
 * 그래서 {@code safety ← (도메인 모듈 참조 X)} 가 유지되고, 이미 허용된 {@code authoring ← safety} 와
 * 순환이 생기지 않는다.
 *
 * <p><b>SPI 가 비어 있을 때는 통과시키지 않는다.</b> 구현 빈이 없으면 부팅 실패, 런타임 조회 실패는
 * 차단(fail-closed) + {@code ERROR} + 알람이다. 세이프티에서 fail-open 은 장애가 곧 검수 우회이며,
 * 블록리스트를 못 읽는 상태에서 통과시키는 것은 블록리스트가 없는 것과 같다 (I-2).
 */
@ApplicationModule(allowedDependencies = "common")
package com.neowadaeum.safety;

import org.springframework.modulith.ApplicationModule;

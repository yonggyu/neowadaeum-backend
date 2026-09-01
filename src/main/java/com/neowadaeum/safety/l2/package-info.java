/**
 * L2 — AI 응답 직후 판정 (B-30).
 *
 * <p><b>이 패키지는 safety 모듈의 API 다</b> — {@code @NamedInterface} 로 명시적으로 노출한다
 * (ADR-0005). §4.3 파이프라인이 L2 를 부르므로 {@code play} 가 판정기와 결과 타입을 참조해야 한다.
 * L0 · L1 · L3 판정기는 다른 패키지에 생기며 그것들은 노출하지 않는다.
 *
 * <p><b>S-11 — 이 패키지의 코드·주석·테스트 이름에 블록리스트 실제 항목이나 우회 표기 예시를
 * 적지 않는다.</b> 실제 문자열은 테스트 픽스처에만 둔다.
 */
@NamedInterface("l2")
package com.neowadaeum.safety.l2;

import org.springframework.modulith.NamedInterface;

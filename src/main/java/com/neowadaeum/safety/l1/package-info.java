/**
 * L1 — <b>사람이 넣은 텍스트</b>를 서버에 들이기 전에 본다 (B-43, I-17, R14.1).
 *
 * <p>L2 가 <b>AI 가 내놓은 것</b>을 보는 자리라면 여기는 그 반대편이다. 이 서비스에서 사람이
 * 임의의 문자열을 넣는 자리는 관리자 자유입력과 UGC 원고뿐이며, 그 둘이 이 문을 지난다.
 *
 * <p><b>{@code @NamedInterface} 로 명시적으로 노출한다</b> (ADR-0005 와 같은 형태). 판정기
 * 내부는 열지 않는다.
 *
 * <p><b>S-11 — 이 패키지의 코드·주석·테스트 이름에 블록리스트 실제 항목이나 우회 표기 예시를
 * 적지 않는다.</b> 실제 문자열은 테스트 픽스처에만 둔다.
 */
@NamedInterface("l1")
package com.neowadaeum.safety.l1;

import org.springframework.modulith.NamedInterface;

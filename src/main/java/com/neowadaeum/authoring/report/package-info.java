/**
 * 신고와 자동 정지 (§8.4, R8.9, 세이프티 L3, B-57).
 *
 * <p>세이프티 L0~L2 는 <b>만들어지는 시점</b>에 본다. 이미 게시된 작품에 대해서는 사람이
 * 알려 주는 길뿐이며, 그것이 L3 다.
 *
 * <p><b>S-11 — 임계값을 코드·주석·응답 어디에도 적지 않는다.</b> 값은
 * {@code service_config} 에 있다 (§13-12). 알면 그 아래로 관리할 수 있다.
 *
 * <p>{@code @NamedInterface} 로 <b>관리자에게만</b> 열린다 (§13-62, 이슈 #316). 검수자가
 * 무엇이 신고됐는지 읽는 길이며, 그 밖의 모듈은 여전히 이 패키지를 보지 못한다 — 신고는
 * 이용자가 쓴 것이고 <b>보는 문이 늘어나는 만큼 새는 자리가 늘어난다.</b>
 */
@NamedInterface("report")
package com.neowadaeum.authoring.report;

import org.springframework.modulith.NamedInterface;

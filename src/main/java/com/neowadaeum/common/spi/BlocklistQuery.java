package com.neowadaeum.common.spi;

import java.util.List;

/**
 * 블록리스트 조회 SPI (ADR-0002).
 *
 * <p><b>{@code blocklist_entry} 는 authoring 이 소유한다.</b> safety 는 이 인터페이스로 <b>읽기만</b>
 * 하며 {@code safety → authoring} 참조를 만들지 않는다 (§5.4).
 *
 * <p><b>fail-closed 가 이 계약의 일부다</b> (ADR-0002).
 *
 * <ul>
 *   <li>구현 빈이 없으면 <b>부팅에 실패한다.</b> 조용히 뜨는 것보다 안 뜨는 게 낫다
 *   <li>런타임 조회가 실패하면 <b>차단한다.</b> 세이프티에서 fail-open 은 장애가 곧 검수 우회다
 * </ul>
 *
 * <p><b>캐싱과 무효화는 구현의 책임이다.</b> 판정은 매 턴 일어나므로 호출 비용이 그대로 턴 지연이
 * 된다. 운영 중 갱신(R9.4)과 그 무효화 경로는 데이터를 소유한 쪽이 갖는다 — B-49.
 */
public interface BlocklistQuery {

	/**
	 * 정규화된 항목 전체를 돌려준다.
	 *
	 * <p>매칭 판단은 호출자(safety)가 한다. 여기서 "포함하는가"까지 결정하면 판정 규칙이 데이터를
	 * 소유한 모듈로 새어 나가고, 그러면 세이프티 규칙이 두 곳에 있게 된다.
	 *
	 * @throws RuntimeException 조회 실패. 호출자는 이것을 <b>차단</b>으로 다룬다
	 */
	List<BlocklistEntry> findAll();
}

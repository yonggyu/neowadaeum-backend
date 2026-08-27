package com.neowadaeum.common.spi;

/**
 * AI 사전 고지가 노출된 화면 (§2.7, R11.3).
 *
 * <p>값을 늘리려면 마이그레이션의 CHECK 제약도 함께 넓혀야 한다. 코드만 늘리면 저장 시점에
 * 제약 위반으로 터진다 — 그것이 의도한 동작이다.
 *
 * <p><b>{@code common/spi} 에 있는 이유</b> (B-14). 이력을 <b>남기는</b> 것은 {@code identity} 이고
 * 노출을 <b>알리는</b> 것은 화면을 가진 모듈들이다 ({@code play} · 이후 {@code catalog} 조회 API).
 * 같은 enum 을 양쪽에 복제하면 값이 갈라지는 날이 온다.
 */
public enum NoticeSurface {

	LANDING,

	LIBRARY,

	DETAIL,

	PLAY
}

package com.neowadaeum.identity.domain;

/**
 * AI 사전 고지가 노출된 화면 (§2.7, R11.3).
 *
 * <p>값을 늘리려면 마이그레이션의 CHECK 제약도 함께 넓혀야 한다. 코드만 늘리면 저장 시점에
 * 제약 위반으로 터진다 — 그것이 의도한 동작이다.
 */
public enum NoticeSurface {

	LANDING,

	LIBRARY,

	DETAIL,

	PLAY
}

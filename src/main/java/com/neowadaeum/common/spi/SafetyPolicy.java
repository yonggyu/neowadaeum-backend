package com.neowadaeum.common.spi;

/** 카테고리에 적용되는 처리 정책 (§9.2). */
public enum SafetyPolicy {

	/** 재생성 없이 즉시 차단한다. */
	BLOCK_IMMEDIATELY,

	/** 재생성 1회 후에도 걸리면 차단한다. */
	REGENERATE_ONCE,

	/**
	 * 생성물은 마스킹 후 통과한다.
	 *
	 * <p><b>가릴 수 있을 때만이다.</b> 마스킹은 탐지 위치를 알아야 하므로 <b>블록리스트 대조가
	 * 찾은 자리</b>에만 붙는다 — 의미 기반 분류가 같은 카테고리를 말했다면 그 자리는 아무도
	 * 모르고, 그때는 가리지 않고 재생성한다 (§13-46).
	 */
	MASK
}

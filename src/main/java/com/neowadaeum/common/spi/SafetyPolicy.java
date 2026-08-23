package com.neowadaeum.common.spi;

/** 카테고리에 적용되는 처리 정책 (§9.2). */
public enum SafetyPolicy {

	/** 재생성 없이 즉시 차단한다. */
	BLOCK_IMMEDIATELY,

	/** 재생성 1회 후에도 걸리면 차단한다. */
	REGENERATE_ONCE,

	/** 생성물은 마스킹 후 통과한다. 규칙 기반 대조만으로는 구현할 수 없다 — 미구현. */
	MASK
}

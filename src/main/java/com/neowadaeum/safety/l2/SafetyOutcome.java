package com.neowadaeum.safety.l2;

/**
 * L2 판정 결과 (§9.1, §9.2).
 *
 * <p>{@code turn.safety_verdict}(R9.3) 로의 변환은 오케스트레이터가 한다 — 재생성을 실제로
 * 수행하는 주체가 그쪽이고, 재생성 후 통과했는지 여부는 여기서 알 수 없다.
 */
public enum SafetyOutcome {

	/** 걸린 것이 없다. */
	PASS,

	/** 즉시차단 카테고리다. <b>재생성 없이</b> 차단한다 (§9.2, B-30 DoD). */
	BLOCK,

	/** 재생성 1회 대상이다. 재생성 후에도 걸리면 차단한다. */
	REGENERATE
}

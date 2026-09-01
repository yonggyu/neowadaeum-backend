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

	/**
	 * 가린 뒤 통과한다 (§9.2 — 타인 개인정보).
	 *
	 * <p><b>가린 본문은 판정과 함께 온다</b> ({@link SafetyJudgement#masked()}). 부르는 쪽이
	 * 원문을 저장하거나 내보내면 이 판정은 아무것도 하지 않은 것이 된다.
	 *
	 * <p><b>이 값이 나오는 조건은 하나다 — 서버가 자리를 알고 있을 때.</b> 자리를 모르면
	 * {@link #REGENERATE} 로 내려간다.
	 */
	MASKED,

	/** 즉시차단 카테고리다. <b>재생성 없이</b> 차단한다 (§9.2, B-30 DoD). */
	BLOCK,

	/** 재생성 1회 대상이다. 재생성 후에도 걸리면 차단한다. */
	REGENERATE
}

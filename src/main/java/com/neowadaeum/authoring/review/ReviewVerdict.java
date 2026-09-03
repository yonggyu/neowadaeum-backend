package com.neowadaeum.authoring.review;

/** 검수 결과 (§2.4, R8.7). */
public enum ReviewVerdict {

	PASS,

	REJECT,

	/** 판단을 미룬다. 인간 검수가 쓰는 값이다 (B-55). */
	HOLD,

	/**
	 * <b>사람이 손으로 내린다</b> (§13-64, R8.9).
	 *
	 * <p>자동 정지는 신고 임계가 한다 (R8.9). 그런데 <b>임계에 닿지 않은 것을 사람이 보고
	 * 내려야 할 때</b>가 있고, 그 손이 없으면 <b>임계만이 유일하게 내리는 길</b>이 된다 —
	 * "자동으로 내린 것을 자동으로 올리지 않는다"는 규칙이 있는 이상 그 반대 방향에도 사람
	 * 손이 필요하다.
	 *
	 * <p><b>{@link #REJECT} 와 다르다.</b> 반려는 <b>아직 열린 적 없는</b> 작품을 열지 않는
	 * 판정이고, 정지는 <b>이미 열려 있는</b> 작품을 내리는 판정이다. 그래서 반려는 가시성을
	 * {@code private} 으로 지우지만 정지는 가시성을 건드리지 않는다 (§13-41) — 사람이 나중에
	 * 통과시킬 때 돌려놓을 자리가 남아 있어야 한다.
	 */
	SUSPEND;

	public String columnValue() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}
}

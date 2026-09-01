package com.neowadaeum.authoring.review;

/** 검수 결과 (§2.4, R8.7). */
public enum ReviewVerdict {

	PASS,

	REJECT,

	/** 판단을 미룬다. 인간 검수가 쓰는 값이다 (B-55). */
	HOLD;

	public String columnValue() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}
}

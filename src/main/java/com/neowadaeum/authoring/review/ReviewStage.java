package com.neowadaeum.authoring.review;

/** 어느 단계의 검수인가 (§2.4). */
public enum ReviewStage {

	/** 자동 검수 (L1). 사람이 없다. */
	AUTO,

	/** 인간 검수 (B-55). {@code public} 은 이것을 반드시 거친다 (R8.6). */
	HUMAN;

	public String columnValue() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}
}

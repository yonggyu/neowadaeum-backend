package com.neowadaeum.authoring.draft;

/**
 * 입력 단계 검수 결과 (R8.2, R8.3).
 *
 * <p><b>{@code blocked} 는 서버가 다음 단계를 거부하는 상태다</b> — 클라이언트 검증에만
 * 의존하지 않는다 (R8.3). {@code warned} 는 알리되 막지 않는다.
 */
public enum DraftSafetyState {

	/** 걸린 것이 없다. */
	CLEAN,

	/** 알린다. 막지는 않는다. */
	WARNED,

	/** 다음 단계로 가지 못한다. */
	BLOCKED;

	public String columnValue() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}
}

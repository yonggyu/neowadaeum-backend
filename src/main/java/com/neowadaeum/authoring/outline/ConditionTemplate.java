package com.neowadaeum.authoring.outline;

/**
 * 작성자가 고를 수 있는 조건 (R7.16).
 *
 * <p><b>작성자가 조건식을 직접 쓰지 않는다.</b> DSL 을 열면 두 가지가 따라온다 — 아무도 못 쓰는
 * 화면이 되거나, 쓸 수 있는 사람이 <b>조건 평가기의 미정의 동작</b>을 찾아낸다.
 *
 * <p><b>목록이 짧은 것은 의도다.</b> §4.5 · §4.6 의 조건 문법 전부를 노출할 이유가 없다 —
 * 필요가 실제로 생기면 그때 늘린다.
 */
public enum ConditionTemplate {

	/** 특정 인물의 호감도가 임계 이상. */
	AFFINITY_AT_LEAST,

	/** 특정 플래그를 갖고 있다. */
	HAS_FLAG,

	/** 특정 플래그를 갖고 있지 않다. */
	LACKS_FLAG,

	/** 턴 수가 임계 이상. */
	TURN_AT_LEAST;

	public String key() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}
}

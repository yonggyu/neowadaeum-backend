package com.neowadaeum.common.spi;

/**
 * 열람 자체가 기록 대상인 것 (R12.3, S-5).
 *
 * <p><b>목록이 짧은 것은 의도다.</b> 여기 있는 것은 "읽는 행위가 곧 사건"인 자원이며, 그 판단은
 * 늘리는 쪽이 아니라 <b>줄이는 쪽</b>으로 기운다 — 모든 조회를 기록하면 기록이 소음이 되고,
 * 그러면 정작 봐야 할 열람이 묻힌다.
 */
public enum AuditedResource {

	/** AI 호출 원문. <b>원문이 여기에만 있다</b> — 그래서 읽는 것 자체가 사건이다. */
	AI_CALL_LOG("ai_call_log"),

	/** 검수 전 UGC 원고. 작성자 말고는 볼 수 없어야 하는 것이다 (I-8). */
	STORY_DRAFT("story_draft");

	private final String columnValue;

	AuditedResource(String columnValue) {
		this.columnValue = columnValue;
	}

	/** DB 의 CHECK 가 받는 표기. 값을 두 곳에 적지 않기 위해 여기 하나에 둔다. */
	public String columnValue() {
		return this.columnValue;
	}
}

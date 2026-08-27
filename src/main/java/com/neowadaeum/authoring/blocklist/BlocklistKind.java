package com.neowadaeum.authoring.blocklist;

import com.neowadaeum.common.spi.SafetyCategory;

/**
 * 블록리스트 항목이 <b>무엇인가</b> (§2.4).
 *
 * <p><b>세이프티 분류와 다른 축이다.</b> 종류는 "그것이 어떤 것인가"이고 분류는 "왜 막는가"다 —
 * 둘을 한 값으로 합치면 관리 화면에서 항목을 정리할 수 없다.
 *
 * <p>둘 사이의 대응은 §13-31 이 정한다. 원문에 없는 결정이며 기본 채택안이다.
 */
public enum BlocklistKind {

	/** 실존 작품명. 그대로 베끼는 것을 막는다 (R9.2). */
	IP_TITLE(SafetyCategory.IP_REPLICATION),

	/** 실존 작품의 인물명. 같은 이유다. */
	CHARACTER(SafetyCategory.IP_REPLICATION),

	/** 실존 인물. 그 사람에게 일어나는 이야기를 쓰지 않는다. */
	REAL_PERSON(SafetyCategory.REAL_PERSON_HARM),

	/**
	 * 그 밖의 표현.
	 *
	 * <p><b>포괄 항목이다.</b> 15세 등급을 넘는 표현이 여기로 오며, 더 잘게 나눌 필요가
	 * 생기면 그때 종류를 늘린다 — 지금 나누면 <b>운영자가 어디에 넣을지 매번 고민한다.</b>
	 */
	PHRASE(SafetyCategory.RATING_EXCEEDED);

	private final SafetyCategory category;

	BlocklistKind(SafetyCategory category) {
		this.category = category;
	}

	/** 이 종류에 걸렸을 때 판정기가 붙일 분류. */
	public SafetyCategory category() {
		return this.category;
	}

	/** DB 표기(소문자)로의 변환은 컨버터 한 곳에서만 한다. */
	public String columnValue() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}
}

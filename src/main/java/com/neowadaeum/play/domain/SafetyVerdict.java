package com.neowadaeum.play.domain;

import java.util.Locale;

/**
 * 턴에 내려진 Safety L2 판정 (§2.5, R9.3).
 *
 * <p><b>I-2 — AI 응답은 L2 통과 전까지 사용자에게 도달하지 않는다.</b> 이 값은 그 판정이 실제로
 * 있었다는 기록이며, 없으면 어떤 턴이 수정본인지 사후에 알 수 없다.
 *
 * <p><b>기본값을 만들지 않는다.</b> 판정을 거치지 않은 턴에 {@link #PASS} 를 넣는 편의를 두면
 * "검수받지 않음"과 "통과함"이 같은 값이 되고, 그 순간 이 컬럼은 아무것도 증명하지 못한다.
 *
 * <p>DB 값은 소문자다({@link SafetyVerdictConverter}). 마이그레이션의 CHECK 제약과 정확히 같아야 한다.
 */
public enum SafetyVerdict {

	/** 그대로 통과했다. */
	PASS,

	/** 검수기가 수정한 본문이 전달됐다. */
	REVISED,

	/** 차단됐다. 사용자에게는 차단 사유를 노출하지 않는다 (R9.6). */
	BLOCKED;

	/** DB 에 저장되는 표기. */
	public String dbValue() {
		return name().toLowerCase(Locale.ROOT);
	}

	/**
	 * DB 표기를 판정으로 되돌린다.
	 *
	 * <p>모르는 값을 기본값으로 흡수하지 않는다. 세이프티 필드에서 조용한 폴백은 검수 실패를
	 * 통과로 바꿔 놓는다 — 어긋났다면 그 사실이 드러나야 한다.
	 */
	public static SafetyVerdict from(String dbValue) {
		for (SafetyVerdict verdict : values()) {
			if (verdict.dbValue().equals(dbValue)) {
				return verdict;
			}
		}
		throw new IllegalArgumentException("unknown safety verdict: " + dbValue);
	}
}

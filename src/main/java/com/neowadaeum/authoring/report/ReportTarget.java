package com.neowadaeum.authoring.report;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Locale;

/**
 * 무엇을 신고했는가 (§13.9).
 *
 * <p><b>둘은 같은 것이 아니다.</b> 작품 신고는 <b>쓰인 것</b>에 대한 것이고, 턴 신고는 한
 * 사람의 플레이에서 <b>생성된 텍스트</b>에 대한 것이다 — 세 사람이 서로 다른 턴을 신고한 것과
 * 세 사람이 그 작품을 신고한 것은 다른 사실이다.
 */
public enum ReportTarget {

	/** 작품. <b>정지 임계에 세는 것은 이쪽뿐이다</b> (R8.9). */
	STORY,

	/** 한 턴의 생성 결과. 사후 검수(B-59)의 재료가 된다. */
	TURN;

	public String columnValue() {
		return name().toLowerCase(Locale.ROOT);
	}

	/**
	 * 계약이 소문자로 적었다 (§13.9) — 열거형 이름을 그대로 받지 않는다.
	 *
	 * <p><b>모르는 표기는 거절이다.</b> 조용히 기본값으로 떨어뜨리면 사용자가 고른 것과 다른
	 * 대상이 신고된다.
	 */
	@JsonCreator
	public static ReportTarget fromWireValue(String value) {
		for (ReportTarget target : values()) {
			if (target.columnValue().equals(value)) {
				return target;
			}
		}
		throw new IllegalArgumentException("unknown report target");
	}
}

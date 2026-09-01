package com.neowadaeum.authoring.report;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Locale;

/**
 * 왜 신고했는가 (§13.9).
 *
 * <p>값 목록은 마이그레이션의 CHECK 와 같아야 한다 — 여기 있는데 거기 없으면 저장에서
 * 거절된다.
 *
 * <p><b>세이프티 카테고리와 다른 축이다.</b> 이것은 <b>사용자가 고른 이유</b>이고, 판정
 * 카테고리는 검수자와 판정기가 쓰는 어휘다. 둘을 하나로 합치면 사용자가 우리 분류 체계를
 * 배워야 신고할 수 있게 된다.
 */
public enum ReportReason {

	INAPPROPRIATE,

	IP_VIOLATION,

	REAL_PERSON,

	OTHER;

	public String columnValue() {
		return name().toLowerCase(Locale.ROOT);
	}

	/** 계약이 소문자로 적었다 (§13.9). 모르는 표기는 거절이다. */
	@JsonCreator
	public static ReportReason fromWireValue(String value) {
		for (ReportReason reason : values()) {
			if (reason.columnValue().equals(value)) {
				return reason;
			}
		}
		throw new IllegalArgumentException("unknown report reason");
	}
}

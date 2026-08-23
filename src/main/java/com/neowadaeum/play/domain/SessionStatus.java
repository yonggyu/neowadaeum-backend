package com.neowadaeum.play.domain;

import java.util.Locale;

/**
 * 세션 상태 (§13-6 정정).
 *
 * <p><b>{@code in_progress} 는 상태가 아니다.</b> 상위 문서의 {@code GET /me/sessions?status=in_progress}
 * 는 API 쿼리 파라미터일 뿐이고, 저장되는 값은 여기 넷뿐이다. 둘을 같은 것으로 보면 조회가 조용히
 * 0건을 돌려준다 — 그래서 §13-6 이 이것을 정정 항목으로 남겼다.
 *
 * <p>DB 값은 소문자다({@link SessionStatusConverter}). 마이그레이션의 CHECK 제약과 문자열이 정확히
 * 같아야 하며, 어긋나면 저장 시점에 제약 위반으로 터진다.
 */
public enum SessionStatus {

	/** 진행 중. 작품당 1개만 존재할 수 있다 (§13-9 partial unique index). */
	ACTIVE,

	/** 엔딩에 도달해 종료됐다 (§4.6). {@code completed_at} 이 함께 기록된다. */
	COMPLETED,

	/** {@code restart=true} 또는 사용자 삭제로 버려졌다 (§13-9). */
	ABANDONED,

	/** 90일 무활동으로 만료됐다 (§4.7). */
	EXPIRED;

	/** DB 에 저장되는 표기. */
	public String dbValue() {
		return name().toLowerCase(Locale.ROOT);
	}

	/**
	 * DB 표기를 상태로 되돌린다.
	 *
	 * <p>모르는 값을 기본값으로 흡수하지 않는다. CHECK 제약을 통과한 값만 들어오므로, 여기서 예외가
	 * 나면 마이그레이션과 이 enum 이 어긋났다는 뜻이고 그 사실이 드러나야 한다.
	 */
	public static SessionStatus from(String dbValue) {
		for (SessionStatus status : values()) {
			if (status.dbValue().equals(dbValue)) {
				return status;
			}
		}
		throw new IllegalArgumentException("알 수 없는 세션 상태다: " + dbValue);
	}
}

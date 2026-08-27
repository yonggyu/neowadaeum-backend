package com.neowadaeum.authoring.review;

/**
 * 누가 볼 수 있는가 (§2.3, R8.6).
 *
 * <p><b>{@code public} 만 인간 검수를 요구한다</b> (R8.6). 나머지는 자동 검수로 승인된다 —
 * 링크를 아는 사람에게만 보이는 작품에 사람을 붙일 이유가 없다.
 */
public enum Visibility {

	/** 작성자만. */
	PRIVATE,

	/** 링크를 아는 사람만. 라이브러리에 뜨지 않는다. */
	UNLISTED,

	/** 누구나. <b>인간 검수 필수</b> (R8.6). */
	PUBLIC;

	public String columnValue() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}
}

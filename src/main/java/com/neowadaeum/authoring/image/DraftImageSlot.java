package com.neowadaeum.authoring.image;

import java.util.Locale;

/**
 * 이미지가 앉는 자리 (#315). 작품 커버(1단계)와 캐릭터 초상(3단계) 둘뿐이다.
 *
 * <p><b>자리 이름이 키의 한 마디가 된다.</b> 자유 문자열로 두면 그것이 곧 경로가 된다.
 */
public enum DraftImageSlot {

	COVER, PORTRAIT;

	/** 모르는 이름이면 {@code null} 이다. */
	public static DraftImageSlot of(String value) {
		for (DraftImageSlot slot : values()) {
			if (value != null && slot.name().equalsIgnoreCase(value.trim())) {
				return slot;
			}
		}
		return null;
	}

	/** 키에 들어가는 표기. 객체 키는 대소문자를 구분한다. */
	public String segment() {
		return name().toLowerCase(Locale.ROOT);
	}
}

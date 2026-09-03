package com.neowadaeum.authoring.outline;

import java.util.Locale;

/**
 * 조건 템플릿이 요구하는 입력의 종류 (R7.16, §13-35 · §13-56).
 *
 * <p><b>화면이 무엇을 그려야 하는지를 말한다.</b> 자유 텍스트 입력이 아니다 — 셋 다 <b>고르는</b>
 * 것이며, 무엇을 고르는지가 다르다.
 *
 * <p><b>일반 문자열 타입을 두지 않는다.</b> 두는 순간 화면은 자유 입력을 그리게 되고, 그것은
 * 작성자가 조건식을 직접 쓰는 것과 같아진다 — {@link ConditionTemplate} 이 막으려 한 바로 그것이다.
 */
public enum ConditionParameterType {

	/** 원고의 캐릭터 하나. 선택지는 <b>그 원고의 캐릭터 목록</b>에서 온다 — 서버가 목록을 내려주지 않는다. */
	CHARACTER,

	/** 원고가 선언한 플래그 이름 하나. 선택지는 그 원고의 플래그 목록에서 온다. */
	FLAG,

	/** 정수 임계값. 호감도 임계와 턴 수가 쓴다. */
	INTEGER;

	/** 계약 표기. 소문자다 — 계약의 다른 열거형과 같은 규약이다. */
	public String key() {
		return name().toLowerCase(Locale.ROOT);
	}
}

package com.neowadaeum.ai.provider;

import java.util.Locale;

/**
 * AI 호출의 용도 (R3.6, §13-4).
 *
 * <p><b>비용이 갈리는 축이다.</b> R3.6 은 <b>턴 생성에 고성능 모델, 요약·검수·아웃라인에 저비용
 * 모델</b>을 쓰라고 한다. 그 분리를 표현할 이름이 없으면 네 용도가 한 설정값을 공유하게 되고,
 * 그러면 <b>요약 한 번이 턴 생성만큼 든다.</b>
 *
 * <p><b>{@code ai_call_log.purpose} 의 CHECK 제약과 같은 축이다</b> (§13-4). 두 곳이 어긋나면
 * 기록이 통계에서 조용히 빠지므로 {@link #wireValue()} 가 그 값을 만들고, 어긋남은 테스트가 잡는다.
 */
public enum AiPurpose {

	/** 턴 본문 생성 (§4.3). <b>고성능 모델</b>이 붙는 자리다. */
	TURN,

	/** 오래된 턴의 압축 (R4.5, B-34). 사용자가 기다리지 않으므로 저비용으로 충분하다. */
	SUMMARY,

	/**
	 * Safety L2 판정 (B-30).
	 *
	 * <p><b>생성 모델과 별개여야 한다</b> (I-12) — 그래서 이 값이 별도로 존재한다. 같은 값을 쓰도록
	 * 설정할 수는 있지만, 그것은 <b>설정의 선택</b>이지 구조가 강요하는 것이 아니다.
	 */
	SAFETY,

	/** UGC 챕터·엔딩 초안 (R7.14, B-52). */
	OUTLINE;

	/**
	 * DB 와 로그에 쓰는 표기.
	 *
	 * <p>열거형 이름을 그대로 쓰지 않는다. {@code ai_call_log.purpose} 의 CHECK 가 소문자를
	 * 요구하며, 대소문자 하나로 기록이 거부되는 것은 <b>런타임에야 드러나는 종류의 실수</b>다.
	 */
	public String wireValue() {
		return name().toLowerCase(Locale.ROOT);
	}
}

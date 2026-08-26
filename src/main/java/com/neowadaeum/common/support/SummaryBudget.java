package com.neowadaeum.common.support;

/**
 * 요약에 허용된 토큰 (§4.3, R4.5).
 *
 * <p><b>두 모듈이 같은 답을 받아야 하는 값이다</b> (#82 · #97 과 같은 근거). 프롬프트 조립기는 이
 * 값으로 <b>실을 수 있는지</b>를 판단하고(§4.4), 요약 파이프라인은 같은 값으로 <b>재압축할지</b>를
 * 판단한다 (R4.5). 두 곳이 다른 숫자를 보면 <b>저장 시점에 통과한 요약이 조립 시점에 예산을
 * 넘는다</b> — 그리고 그 증상은 매 턴 재압축이 도는 형태로 나타난다.
 *
 * <p>설정으로 빼지 않는다. §4.3 의 표가 정한 계약값이며 배포마다 정하는 값이 아니다.
 */
public final class SummaryBudget {

	/** §4.3 — SUMMARY 레이어의 상한. */
	public static final int MAX_TOKENS = 600;

	private SummaryBudget() {
	}
}

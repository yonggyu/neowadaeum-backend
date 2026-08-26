package com.neowadaeum.common.support;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 최근 턴의 세 경계 (§13-2, B-20).
 *
 * <p>§13-2 가 기획서와 요구사항의 불일치(5턴 대 8턴)를 정리하면서 셋을 갈라 놓았다. <b>서로 다른
 * 것을 가리키므로 한 값으로 합칠 수 없다.</b>
 *
 * <table border="1">
 *   <caption>세 경계</caption>
 *   <tr><th>값</th><th>기본</th><th>뜻</th><th>읽는 곳</th></tr>
 *   <tr><td>{@code verbatim}</td><td>2</td>
 *       <td>가장 최근 몇 턴을 <b>본문 원문</b>으로 싣는가. 나머지는 압축본이다 —
 *           1,500토큰 안에 원문 5턴은 들어가지 않는다</td>
 *       <td>{@link PromptAssembler}</td></tr>
 *   <tr><td>{@code inPrompt}</td><td>5</td><td>프롬프트에 싣는 턴 수 (R4.7)</td>
 *       <td>{@link PromptAssembler}</td></tr>
 *   <tr><td>{@code summaryMerge}</td><td>8</td>
 *       <td>이보다 오래된 턴이 요약에 병합된다 (R4.5). 6~8턴 구간은 <b>완충지대</b>다 —
 *           요약 압축이 비동기라(R4.6) 지연될 수 있다</td>
 *       <td>요약 파이프라인 (B-34)</td></tr>
 * </table>
 *
 * <p><b>설정으로 둔 이유.</b> §13-2 는 완충 구간(8)과 원문/압축 경계(2)를 <b>`[결정 필요]` 로 남기고
 * B-46 실측 후 조정</b>한다고 적었다. 코드 상수로 박아 두면 그 조정이 배포가 된다.
 *
 * <p><b>{@code common} 이 소유한다</b> (#97, §5.4). 이전에는 {@code ai/prompt} 에 있었고
 * {@code summaryMerge} 는 그 패키지가 읽지 않았다 — <b>읽어야 할 쪽이 {@code play} 였다.</b>
 * {@code play} 는 {@code ai} 를 참조할 수 없어(ADR-0006) 같은 숫자 8 을 상수로 복제했고, 그러면
 * {@code inPrompt} 를 그보다 크게 설정했을 때 <b>조립기가 있는 만큼만 받고 그 사실을 알지
 * 못한다.</b> {@code TokenCounter} 를 옮긴 것과 같은 근거다 (#82) — 두 모듈이 같은 답을 받아야
 * 하는 순수 값이다.
 *
 * <p>설정 접두어는 {@code ai.prompt.recent-turns} 그대로다. §13-2 가 그 이름으로 부르며, 값이
 * 사는 모듈이 바뀌었다고 배포 설정 키를 바꿀 이유는 없다.
 *
 * <p>{@code summaryMerge} 는 프롬프트 조립이 읽지 않는다. 그럼에도 여기 함께 두는 것은 <b>셋이 한
 * 규칙의 세 부분</b>이기 때문이다 — 따로 두면 B-46 이 하나만 고치고 관계가 깨진다.
 */
@ConfigurationProperties("ai.prompt.recent-turns")
public record RecentTurnsProperties(Integer verbatim, Integer inPrompt, Integer summaryMerge) {

	private static final int DEFAULT_VERBATIM = 2;

	private static final int DEFAULT_IN_PROMPT = 5;

	private static final int DEFAULT_SUMMARY_MERGE = 8;

	public RecentTurnsProperties {
		verbatim = verbatim == null ? DEFAULT_VERBATIM : verbatim;
		inPrompt = inPrompt == null ? DEFAULT_IN_PROMPT : inPrompt;
		summaryMerge = summaryMerge == null ? DEFAULT_SUMMARY_MERGE : summaryMerge;

		if (verbatim < 0 || inPrompt <= 0 || summaryMerge <= 0) {
			throw new IllegalArgumentException("recent-turns boundaries must be positive");
		}
		// 셋의 순서가 뒤집히면 §13-2 의 구조가 무너진다 — 원문 구간이 프롬프트 구간보다 넓거나,
		// 완충지대가 음수가 된다. 조정은 열어 두되 모순은 부팅에서 막는다.
		if (verbatim > inPrompt) {
			throw new IllegalArgumentException("verbatim must not exceed inPrompt");
		}
		if (inPrompt > summaryMerge) {
			throw new IllegalArgumentException("inPrompt must not exceed summaryMerge");
		}
	}

	/** 기본 경계 (§13-2 의 채택안). */
	public static RecentTurnsProperties defaults() {
		return new RecentTurnsProperties(null, null, null);
	}
}

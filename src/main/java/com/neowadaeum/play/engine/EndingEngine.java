package com.neowadaeum.play.engine;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 엔딩 도달을 판정한다 (B-29 / S-7, §4.6).
 *
 * <p><b>R7.9 — AI 임의 종료는 허용하지 않는다.</b> 이 클래스에는 {@code endingSuggested} 를
 * <b>받을 파라미터가 없다.</b> 무시하는 코드를 쓰는 대신 받을 자리를 만들지 않았다.
 *
 * <p><b>판정 순서 (R7.6, R7.7, §13-16)</b>
 *
 * <ol>
 *   <li>{@code condition} 을 가진 엔딩을 {@code ending_no} 오름차순으로 평가한다.
 *       <b>최초 매칭에서 종료를 선언한다.</b>
 *   <li>하나도 매칭되지 않았고 <b>마지막 챕터의 {@code max_turns} 에 도달했다면</b> 기본 엔딩으로 종료한다.
 *   <li>그 외에는 종료하지 않는다.
 * </ol>
 *
 * <p><b>기본 엔딩은 1번 순회에 참여하지 않는다</b> (§13-16 규칙 1). 조건이 없으므로 순회에 넣으면
 * 매 턴 최초 매칭이 되고, 그러면 모든 세션이 1턴에 끝난다. 2번의 시점이 기본 엔딩이 선택되는
 * <b>유일한 시점</b>이다.
 *
 * <p><b>I-15</b> — 난수가 없다. <b>I-20</b> — 도달률은 여기서 계산하지 않는다 (배치, B-39).
 */
public class EndingEngine {

	private final ConditionEvaluator evaluator;

	public EndingEngine(ConditionEvaluator evaluator) {
		this.evaluator = evaluator;
	}

	/**
	 * @param endings              이 작품 버전의 엔딩 전부
	 * @param state                판정 대상 상태
	 * @param lastChapterExhausted 마지막 챕터의 {@code max_turns} 에 도달했는가 (R7.7).
	 *                             집계 근거는 {@code turn.chapter_no} 이며 계산은 호출자(S-9)가 한다
	 */
	public EndingDecision decide(List<EndingDefinition> endings, GameState state, boolean lastChapterExhausted) {
		if (endings == null || endings.isEmpty()) {
			throw new IllegalArgumentException("ending definitions are required");
		}
		if (state == null) {
			throw new IllegalArgumentException("state is required");
		}

		List<EndingDefinition> ordered = endings.stream()
				.sorted(Comparator.comparingInt(EndingDefinition::endingNo))
				.toList();

		// 1 — 조건부 엔딩만 순회한다. 기본 엔딩은 여기 없다 (§13-16 규칙 1).
		for (EndingDefinition ending : ordered) {
			if (ending.defaultEnding()) {
				continue;
			}
			if (this.evaluator.evaluate(ending.condition(), state)) {
				return reached(ending, ordered, false);
			}
		}

		// 2 — R7.7 의 시점에만 폴백한다. 매 턴 폴백하면 초반 턴은 어떤 조건도 만족하지 않으므로
		//     모든 세션이 1턴에 끝난다.
		if (!lastChapterExhausted) {
			return EndingDecision.notReached();
		}

		return ordered.stream()
				.filter(EndingDefinition::defaultEnding)
				.findFirst()
				.map(fallback -> reached(fallback, ordered, true))
				// R2.2 는 "정확히 1개"를 요구하지만 하한은 DB 로 막을 수 없다 (§13-16 규칙 5).
				// 여기서 조용히 넘어가면 세션이 끝나지 못하고 무한히 진행된다 — 실패시킨다.
				.orElseThrow(() -> new IllegalStateException(
						"no default ending to fall back to (R2.2) — the story is not playable"));
	}

	/**
	 * R7.11 — {@code endingIndex} / {@code totalEndings} 는 {@code is_secret = false} 인 엔딩만 센다.
	 *
	 * <p>시크릿을 총계에 넣으면 미도달 엔딩의 존재가 드러난다. 그래서 <b>도달한 엔딩이 시크릿이면
	 * {@code endingIndex} 는 {@code null}</b> 이다 — 셀 자리가 없는데 번호를 주면 그 목적이 깨진다.
	 */
	private static EndingDecision reached(EndingDefinition ending, List<EndingDefinition> ordered, boolean byDefault) {
		List<EndingDefinition> visible = ordered.stream().filter(candidate -> !candidate.secret()).toList();

		Integer index = null;
		if (!ending.secret()) {
			index = visible.indexOf(ending) + 1;
		}

		return new EndingDecision(true, ending, index, visible.size(), byDefault);
	}

	/**
	 * 판정 결과.
	 *
	 * @param reached       종료가 선언됐는가. 응답의 {@code isEnding} 근거다 (R7.8)
	 * @param ending        도달한 엔딩. 미도달이면 {@code null}
	 * @param endingIndex   비시크릿 엔딩 중 1-based 순번. 시크릿 엔딩이면 {@code null} (R7.11)
	 * @param totalEndings  비시크릿 엔딩 수 (R7.11)
	 * @param byDefault     조건 매칭이 아니라 폴백으로 끝났는가 (R7.7). 로그·관측용이다
	 */
	public record EndingDecision(boolean reached, EndingDefinition ending, Integer endingIndex, int totalEndings,
			boolean byDefault) {

		static EndingDecision notReached() {
			return new EndingDecision(false, null, null, 0, false);
		}

		public Optional<EndingDefinition> endingIfReached() {
			return Optional.ofNullable(this.ending);
		}
	}
}

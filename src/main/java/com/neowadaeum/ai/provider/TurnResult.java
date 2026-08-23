package com.neowadaeum.ai.provider;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Provider 가 돌려주는 턴 생성 결과 (S-3).
 *
 * <p><b>전부 제안값이다. 서버가 최종 권한을 갖는다.</b>
 *
 * <ul>
 *   <li><b>I-9</b> — {@code chapter} · {@code turn} 필드가 <b>없다.</b> 서버 전용 필드이므로 Provider 가
 *       돌려줄 자리를 두지 않는다. 값을 무시하는 것이 아니라 애초에 받지 않는다.
 *   <li><b>I-10</b> — {@code chapterAdvanceSuggested} · {@code endingSuggested} 는 <b>참고용</b>이다.
 *       전환과 종료 선언은 서버가 GameState 로 판정한다 (§4.5, §4.6).
 *   <li><b>I-1</b> — {@code choices} 에 {@code choiceId} 가 없다. 발급 주체는 서버다 (B-21).
 *   <li><b>I-11</b> — {@code disabled} · {@code disabledReason} 도 없다. 서버가 판정한다.
 *       현재 채택안은 항상 {@code false} 다 (§13-3 P0).
 *   <li><b>R4.1, R4.2</b> — {@code proposedStateChanges} 는 그대로 병합되지 않는다. 화이트리스트
 *       필터 → clamp → 병합 순서를 서버가 수행한다 (S-5 / B-26).
 * </ul>
 *
 * @param narrative            턴 본문
 * @param choices              선택지. 엔딩 턴이면 빈 목록이다 (§4.6)
 * @param proposedStateChanges AI 가 제안하는 상태 변화. 서버가 필터·clamp 한다.
 *                             <b>{@code Map<String, Integer>} 가 아니라 원시 JSON 이다</b> —
 *                             §5.2 의 {@code stateChanges} 는 수치 델타와 배열 연산자
 *                             ({@code flags.add} 등, §13-9)가 섞인 형태다. 타입을 좁히면
 *                             그 절반을 표현할 수 없고, 실제로 S-9 에서 그 사실이 드러났다
 * @param chapterAdvanceSuggested 챕터 전환 제안. 서버 판정에 구속력이 없다 (R7.1)
 * @param endingSuggested      엔딩 제안 식별자. 조건이 매칭되지 않으면 무시된다 (R7.9). 없으면 {@code null}
 */
public record TurnResult(
		String narrative,
		List<ProposedChoice> choices,
		JsonNode proposedStateChanges,
		boolean chapterAdvanceSuggested,
		String endingSuggested) {

	public TurnResult {
		if (narrative == null || narrative.isBlank()) {
			throw new IllegalArgumentException("narrative is required");
		}
		choices = List.copyOf(choices == null ? List.of() : choices);

	}

	/**
	 * Provider 가 제안하는 선택지. <b>{@code order} 와 {@code text} 뿐이다</b> (§13-3).
	 *
	 * <p>{@code choiceId} 발급과 {@code disabled} 판정은 서버 몫이며 (I-1, I-11), 클라이언트가 보낸
	 * {@code text} 는 어떤 경우에도 신뢰하지 않는다.
	 */
	public record ProposedChoice(int order, String text) {

		public ProposedChoice {
			if (order < 1) {
				throw new IllegalArgumentException("choice order starts at 1");
			}
			if (text == null || text.isBlank()) {
				throw new IllegalArgumentException("choice text is required");
			}
		}
	}
}

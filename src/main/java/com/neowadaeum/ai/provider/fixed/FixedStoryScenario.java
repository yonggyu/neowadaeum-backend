package com.neowadaeum.ai.provider.fixed;

import com.neowadaeum.ai.provider.TurnResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 시나리오 파일 1벌의 표현 (S-3).
 *
 * <p>파일 형식은 <b>JSON</b> 이다. §7.2 는 승인된 3종 외의 신규 {@code *.yml} / {@code *.yaml} 을
 * 금지한다 — 시나리오는 그 예외에 해당하지 않는다.
 *
 * <p>각 {@link Entry} 는 <b>요청</b>을 키로 갖는다. {@code (turnNo, chosenChoiceOrder)} 조합 하나가
 * 정확히 한 응답에 대응하며, 이것이 결정론의 근거다 (I-15). 분기는 같은 {@code turnNo} 에 대해
 * {@code chosenChoiceOrder} 를 달리하는 항목을 두어 표현한다.
 *
 * @param storyVersionRef 이 시나리오가 대신하는 작품 버전
 * @param description     사람이 읽을 설명. 동작에 영향을 주지 않는다
 * @param entries         요청 → 응답 대응표
 */
public record FixedStoryScenario(UUID storyVersionRef, String description, List<Entry> entries) {

	public FixedStoryScenario {
		if (storyVersionRef == null) {
			throw new IllegalArgumentException("storyVersionRef is required");
		}
		if (entries == null || entries.isEmpty()) {
			throw new IllegalArgumentException("a scenario needs at least one entry");
		}
		entries = List.copyOf(entries);
	}

	/**
	 * 요청 하나에 대응하는 고정 응답.
	 *
	 * @param turnNo            요청 시점의 현재 턴 (§4.3 턴 번호 계약). 첫 턴 생성은 {@code 0}
	 * @param chosenChoiceOrder 직전 턴에서 고른 선택지 순서. {@code turnNo == 0} 이면 {@code null}
	 */
	public record Entry(
			int turnNo,
			Integer chosenChoiceOrder,
			String narrative,
			List<Choice> choices,
			Map<String, Integer> proposedStateChanges,
			boolean chapterAdvanceSuggested,
			String endingSuggested) {

		/** 파일 표현의 선택지. {@code order} 와 {@code text} 뿐이다 (§13-3). */
		public record Choice(int order, String text) {
		}

		TurnResult toResult() {
			List<TurnResult.ProposedChoice> proposed = (choices == null ? List.<Choice>of() : choices).stream()
					.map(choice -> new TurnResult.ProposedChoice(choice.order(), choice.text()))
					.toList();

			return new TurnResult(narrative, proposed, proposedStateChanges, chapterAdvanceSuggested, endingSuggested);
		}
	}
}

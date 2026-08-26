package com.neowadaeum.ai.provider.fixed;

import com.neowadaeum.common.spi.SafetyCategory;
import com.neowadaeum.play.port.GeneratedChoice;
import com.neowadaeum.play.port.GeneratedParagraph;
import com.neowadaeum.play.port.GeneratedTurn;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

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
	 * <p><b>파일이 생성 계약을 그대로 쓴다</b> (#84). 별도의 파일 전용 타입을 두지 않는 것은,
	 * 두 벌이 되는 순간 <b>시나리오가 표현할 수 있는 것과 실제 계약이 갈라지기</b> 때문이다 —
	 * 문단 종류와 화자가 파일 형식에서 빠져 있으면 E2E 가 그 손실을 재현하지 못한다.
	 *
	 * @param turnNo            요청 시점의 현재 턴 (§4.3 턴 번호 계약). 첫 턴 생성은 {@code 0}
	 * @param chosenChoiceOrder 직전 턴에서 고른 선택지 순서. {@code turnNo == 0} 이면 {@code null}
	 * @param paragraphs        본문 문단. <b>통 문자열이 아니다</b> (R5.1)
	 * @param safetyCategories  이 응답을 <b>판정기가 무엇으로 보는가</b> (B-30). 비우면 아무것도
	 *                          걸리지 않는다. 세이프티 경로를 E2E 로 재현하려면 여기에 선언한다 —
	 *                          결정론 Provider 에게 "모델이 뭐라고 답할지"를 지어내게 하지 않는다
	 */
	public record Entry(
			int turnNo,
			Integer chosenChoiceOrder,
			List<GeneratedParagraph> paragraphs,
			List<GeneratedChoice> choices,
			JsonNode proposedStateChanges,
			boolean chapterAdvanceSuggested,
			String endingSuggested,
			List<SafetyCategory> safetyCategories) {

		public Entry {
			// 선언하지 않은 시나리오 파일이 대부분이다 — 없는 것과 빈 것을 같게 본다.
			safetyCategories = (safetyCategories != null) ? List.copyOf(safetyCategories) : List.of();
		}

		GeneratedTurn toGeneratedTurn() {
			return new GeneratedTurn(paragraphs, choices, proposedStateChanges, chapterAdvanceSuggested,
					endingSuggested);
		}
	}
}

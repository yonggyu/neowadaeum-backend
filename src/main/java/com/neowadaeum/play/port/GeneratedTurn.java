package com.neowadaeum.play.port;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * 생성된 턴 — <b>{@code play} 가 저장하고 응답할 모양이다</b> (#84, ADR-0006).
 *
 * <p><b>전부 제안값이다. 서버가 최종 권한을 갖는다.</b>
 *
 * <ul>
 *   <li><b>R5.1</b> — 본문은 {@link GeneratedParagraph} 배열이다. <b>통 문자열 자리가 없다.</b>
 *       이전 계약({@code TurnResult.narrative})은 문자열이었고, 저장 직전에
 *       {@code List.of(...)} 로 감싸 <b>R5.1 이 금지한 1개짜리 배열</b>이 됐다
 *   <li><b>I-9</b> — {@code chapter} · {@code turn} 이 <b>없다.</b> 서버 전용 필드이므로 담을
 *       자리를 두지 않는다. 값을 무시하는 것이 아니라 애초에 받지 않는다
 *   <li><b>I-10</b> — {@code chapterAdvanceSuggested} · {@code endingSuggested} 는 <b>참고용</b>이다.
 *       전환과 종료 선언은 서버가 GameState 로 판정한다 (§4.5, §4.6)
 *   <li><b>R4.1, R4.2</b> — {@code proposedStateChanges} 는 그대로 병합되지 않는다. 화이트리스트
 *       필터 → clamp → 병합 순서를 서버가 수행한다
 * </ul>
 *
 * @param paragraphs              본문 문단. 비어 있을 수 없다 (R5.1)
 * @param choices                 선택지. 1~4개 (R5.4). <b>엔딩 턴이라고 비어 오지 않는다</b> —
 *                                종료 선언은 서버가 하므로 생성 측은 자기 턴이 마지막인지 모른다.
 *                                {@code choices: []} 로 바꾸는 것은 엔딩 엔진이다 (R7.8)
 * @param proposedStateChanges    상태 변화 제안. <b>{@code Map<String, Integer>} 가 아니라 원시
 *                                JSON 이다</b> — §5.2 의 {@code stateChanges} 는 수치 델타와 배열
 *                                연산자({@code flags.add} 등, §13-9)가 섞인 형태다
 * @param chapterAdvanceSuggested 챕터 전환 제안. 서버 판정에 구속력이 없다 (R7.1)
 * @param endingSuggested         엔딩 제안 식별자. 조건이 매칭되지 않으면 무시된다 (R7.9)
 */
public record GeneratedTurn(
		List<GeneratedParagraph> paragraphs,
		List<GeneratedChoice> choices,
		JsonNode proposedStateChanges,
		boolean chapterAdvanceSuggested,
		String endingSuggested) {

	public GeneratedTurn {
		if (paragraphs == null || paragraphs.isEmpty()) {
			throw new IllegalArgumentException("paragraphs must not be empty");
		}
		paragraphs = List.copyOf(paragraphs);
		choices = List.copyOf(choices == null ? List.of() : choices);
	}

	/**
	 * 첫 대사의 화자 — {@code turn.speaker_name} 에 넣는 <b>파생값</b>이다 (#84 결정).
	 *
	 * <p>컬럼을 지우지 않고 남긴 이유는, 한 턴에 여러 화자를 <b>외부 계약이 실제로 지원하게 될 때</b>
	 * 별도로 다루기 위해서다. 그전까지 이 값은 문단 배열에서 나오는 파생값이며 <b>진실의 원천이
	 * 아니다</b> — 진실은 {@link #paragraphs()} 다.
	 *
	 * @return 첫 {@code DIALOGUE} 문단의 화자. 대사가 없거나 화자가 없으면 {@code null}
	 */
	public String leadSpeakerName() {
		return this.paragraphs.stream()
				.filter(paragraph -> paragraph.type() == ParagraphType.DIALOGUE)
				.map(GeneratedParagraph::speakerName)
				.filter(name -> name != null && !name.isBlank())
				.findFirst()
				.orElse(null);
	}
}

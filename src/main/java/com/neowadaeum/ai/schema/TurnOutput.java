package com.neowadaeum.ai.schema;

import com.neowadaeum.play.port.GeneratedChoice;
import com.neowadaeum.play.port.GeneratedParagraph;
import com.neowadaeum.play.port.GeneratedTurn;
import com.neowadaeum.play.port.ParagraphType;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Provider 가 돌려준 턴 출력 JSON 의 형태 (§5.2, B-21).
 *
 * <p><b>{@code ai} 모듈 내부 DTO 다. 밖으로 나가지 않는다</b> (§5.4). 이것은 <b>와이어에서 읽은
 * 그대로</b>이고, 모듈 경계를 넘는 것은 {@link GeneratedTurn} 이다. 둘을 하나로 합치지 않는 이유는
 * <b>읽은 것과 서버가 인정한 것을 구분해야</b> 하기 때문이다 — 합치면 "AI 가 보낸 값"과 "서버가
 * 판정한 값"이 같은 필드에 앉는다.
 *
 * <p><b>{@code speakerName} 이 여기서는 턴 단위다.</b> §5.2 · R5.2 의 와이어 형식이 그렇기 때문이며,
 * {@link #toGeneratedTurn()} 이 그것을 각 대사 문단에 복사해 넣는다 (#84 결정).
 *
 * <p><b>I-9 — {@code chapter} · {@code turn} 을 받을 자리가 없다.</b> 응답에 그 이름이 실려 와도
 * 여기에 담기지 않으므로 서버가 읽을 방법이 없다. 값을 무시하는 코드를 두는 것이 아니라 <b>애초에
 * 받지 않는 것</b>이 I-9 의 구조적 보장이다.
 *
 * <p><b>I-1 · I-11 — {@code choiceId} · {@code disabled} 도 없다.</b> 발급과 판정은 서버 몫이며
 * ({@code ChoiceIdIssuer}, §13-3), AI 가 보낸 값이 들어올 자리를 만들면 언젠가 그것이 쓰인다.
 *
 * @param speakerName             화자. <b>nullable 이다</b> — {@code null} 이면 나레이션으로 렌더한다 (R5.2)
 * @param paragraphs              본문 문단. <b>통 문자열이 아니라 배열이다</b> (R5.1)
 * @param choices                 선택지. 1~4개 (R5.4). <b>엔딩 턴이라고 비어 오지 않는다</b> —
 *                                종료 선언은 서버가 하므로 (I-10) AI 는 자기 턴이 마지막인지 모른다.
 *                                {@code choices: []} 로 바꾸는 것은 엔딩 엔진이다 (R7.8)
 * @param stateChanges            상태 변화 제안. <b>원시 JSON 그대로 넘긴다</b> — 수치 델타와 배열
 *                                연산자({@code flags.add}, §13-9)가 섞이므로 타입을 좁히면 표현할 수
 *                                없다. 화이트리스트 · clamp 는 GameState 엔진이 한다 (R4.1, R4.2)
 * @param chapterAdvanceSuggested 챕터 전환 제안. <b>구속력이 없다</b> (R5.7, I-10)
 * @param endingSuggested         엔딩 제안. 조건이 매칭되지 않으면 무시된다 (R7.9). 없으면 {@code null}
 */
public record TurnOutput(
		String speakerName,
		List<Paragraph> paragraphs,
		List<Choice> choices,
		JsonNode stateChanges,
		boolean chapterAdvanceSuggested,
		String endingSuggested) {

	public TurnOutput {
		paragraphs = List.copyOf(paragraphs == null ? List.of() : paragraphs);
		choices = List.copyOf(choices == null ? List.of() : choices);
	}

	/**
	 * 모듈 경계를 넘는 형태로 바꾼다 (#84).
	 *
	 * <p><b>턴 단위 화자를 대사 문단에 복사한다.</b> 와이어는 §5.2 대로 화자가 턴에 하나지만,
	 * 내부 계약은 문단마다 갖는다 — 한 턴에 두 인물의 대사가 섞이는 장면을 턴 단위 모델은 표현할
	 * 수 없기 때문이다. 나레이션에는 화자를 넣지 않는다 (R5.2).
	 *
	 * <p><b>이 메서드가 있는 자리가 요점이다.</b> 변환이 {@code play} 쪽에 있으면 {@code play} 가
	 * 와이어 DTO 를 알아야 하고, 그 순간 "외부 응답 DTO 가 도메인까지 흐르는" 형태가 된다.
	 */
	public GeneratedTurn toGeneratedTurn() {
		List<GeneratedParagraph> converted = this.paragraphs.stream()
				.map(paragraph -> new GeneratedParagraph(paragraph.type(),
						(paragraph.type() == ParagraphType.DIALOGUE) ? this.speakerName : null,
						paragraph.text()))
				.toList();

		return new GeneratedTurn(converted,
				this.choices.stream().map(choice -> new GeneratedChoice(choice.order(), choice.text())).toList(),
				this.stateChanges, this.chapterAdvanceSuggested, this.endingSuggested);
	}

	/**
	 * 본문 한 문단.
	 *
	 * @param type 문단 종류. {@link ParagraphType} 밖의 값은 파서가 거부한다
	 * @param text 문단 본문
	 */
	public record Paragraph(ParagraphType type, String text) {
	}

	/**
	 * Provider 가 제안하는 선택지. <b>{@code order} 와 {@code text} 뿐이다</b> (§13-3).
	 *
	 * @param order 표시 순서. 1부터
	 * @param text  선택지 텍스트
	 */
	public record Choice(int order, String text) {
	}
}

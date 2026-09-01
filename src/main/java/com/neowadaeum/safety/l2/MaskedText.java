package com.neowadaeum.safety.l2;

import java.util.List;

/**
 * 가려진 본문 (§9.2 — 마스킹 후 통과).
 *
 * <p><b>이것은 원문이 아니라 사용자에게 도달할 본문이다.</b> {@link SafetyJudgement} 가 걸린
 * 문자열을 담지 않는 이유(S-3, S-11)는 여기에도 그대로 적용된다 — 이 값을 로그에 남기지 않는다.
 * 저장과 응답에만 쓴다.
 *
 * <p><b>순서와 개수가 입력과 같다.</b> 판정기는 받은 문단·선택지를 그 자리에서 바꾼 목록을
 * 돌려주며, 부르는 쪽은 인덱스로 다시 붙인다. 개수가 달라지면 붙일 방법이 없다.
 *
 * @param paragraphs 가려진 문단 본문
 * @param choices    가려진 선택지 텍스트
 */
public record MaskedText(List<String> paragraphs, List<String> choices) {

	public MaskedText {
		paragraphs = List.copyOf(paragraphs == null ? List.of() : paragraphs);
		choices = List.copyOf(choices == null ? List.of() : choices);
	}
}

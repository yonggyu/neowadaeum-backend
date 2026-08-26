package com.neowadaeum.play.port;

/**
 * 생성된 선택지 — <b>{@code order} 와 {@code text} 뿐이다</b> (§13-3).
 *
 * <p><b>{@code choiceId} 를 담을 자리가 없다</b> (I-1). 발급 주체는 서버이며
 * ({@code ChoiceIdIssuer}), 클라이언트가 보낸 {@code text} 는 어떤 경우에도 신뢰하지 않는다.
 *
 * <p><b>{@code disabled} · {@code disabledReason} 도 없다</b> (I-11). 서버가 GameState 조건으로
 * 판정한다. 자리를 만들면 언젠가 그 값이 쓰인다.
 *
 * @param order 표시 순서. 1부터
 * @param text  선택지 텍스트
 */
public record GeneratedChoice(int order, String text) {

	public GeneratedChoice {
		if (order < 1) {
			throw new IllegalArgumentException("choice order starts at 1");
		}
		if (text == null || text.isBlank()) {
			throw new IllegalArgumentException("choice text is required");
		}
	}
}

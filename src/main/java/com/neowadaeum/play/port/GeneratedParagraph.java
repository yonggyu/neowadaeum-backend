package com.neowadaeum.play.port;

/**
 * 본문 한 문단 (R5.1, R5.2).
 *
 * <p><b>화자가 문단에 붙는다.</b> §5.2 의 와이어 형식은 {@code speakerName} 을 턴 하나에 두지만,
 * 그 모델은 <b>한 턴에 두 인물의 대사가 섞이는 장면을 표현할 수 없다.</b> 내부 계약은 넓은 쪽을
 * 쓰고, 파서가 턴 단위 화자를 각 문단에 복사해 넣는다 (#84 결정).
 *
 * <p><b>프롬프트는 바뀌지 않는다.</b> {@code OUTPUT_SPEC}(B-20)은 §5.2 그대로이며 골든 파일도
 * 그대로다. 나중에 모델에게 문단별 화자를 요구하게 되면 <b>파서만 바뀐다.</b>
 *
 * @param type        문단 종류
 * @param speakerName 화자. <b>nullable 이다</b> — {@code null} 이면 나레이션으로 렌더한다 (R5.2)
 * @param text        문단 본문
 */
public record GeneratedParagraph(ParagraphType type, String speakerName, String text) {

	public GeneratedParagraph {
		if (type == null) {
			throw new IllegalArgumentException("paragraph type is required");
		}
		if (text == null || text.isBlank()) {
			throw new IllegalArgumentException("paragraph text is required");
		}
	}

	/** 화자가 없는 문단. */
	public static GeneratedParagraph narration(String text) {
		return new GeneratedParagraph(ParagraphType.NARRATION, null, text);
	}
}

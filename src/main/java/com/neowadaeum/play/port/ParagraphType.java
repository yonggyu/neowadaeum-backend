package com.neowadaeum.play.port;

/**
 * 본문 문단의 종류 (§5.2).
 *
 * <p><b>열거형인 것이 검증이다.</b> 문자열로 두면 모델이 만들어 낸 종류가 그대로 저장되고,
 * 렌더링은 그것을 만나는 시점에 깨진다.
 */
public enum ParagraphType {

	/** 인물의 대사. {@link GeneratedParagraph#speakerName()} 과 함께 렌더한다. */
	DIALOGUE,

	/** 나레이션. 화자가 없다. */
	NARRATION
}

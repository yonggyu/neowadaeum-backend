package com.neowadaeum.common.spi;

import java.util.List;

/**
 * 의미 기반 분류에 넘길 텍스트 (R9.2 의 2단, B-30).
 *
 * <p><b>판정 대상만 담는다.</b> 세션 · 작품 · 사용자에 관한 것은 하나도 들어가지 않는다 — 판정에
 * 필요 없고, 넣는 순간 그것이 AI 페이로드로 나간다 (I-3). 필드가 없으면 실을 방법도 없다.
 *
 * <p><b>본문과 선택지를 나누지 않는다.</b> 판정 기준이 둘에서 다르지 않고, 나누면 <b>한쪽만
 * 검사하는 호출</b>이 생길 자리가 만들어진다. 선택지 텍스트도 사용자에게 도달하는 문자열이다.
 *
 * @param texts 판정할 문자열들. 비어 있으면 부를 이유가 없다
 */
public record SafetyClassificationRequest(List<String> texts) {

	public SafetyClassificationRequest {
		if (texts == null || texts.isEmpty()) {
			throw new IllegalArgumentException("texts is required");
		}
		texts = List.copyOf(texts);
	}
}

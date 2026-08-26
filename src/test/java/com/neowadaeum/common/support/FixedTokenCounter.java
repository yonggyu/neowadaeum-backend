package com.neowadaeum.common.support;

/**
 * 규칙이 고정된 테스트용 토큰 계산기 (#82).
 *
 * <p><b>골든 파일이 근사 계수에 흔들리지 않게 하는 것이 목적이다.</b> 프롬프트 골든 테스트가
 * {@link ApproximateTokenCounter} 를 쓰면, 계수 한 줄을 조정했을 때 <b>축소 시점이 달라져 골든
 * 파일이 함께 바뀐다</b> — 그러면 "프롬프트를 바꿨다"와 "계수를 바꿨다"가 같은 diff 안에서
 * 구분되지 않는다. 골든 파일의 목적이 정확히 그 구분이므로, 두 변경을 갈라 둔다.
 *
 * <p>규칙은 <b>두 갈래로 고정</b>이다 — 비ASCII 는 글자당 1토큰, ASCII 는 4자당 1토큰. 여기에는
 * 조정 가능한 계수도 안전 여유도 없다. 그것이 이 계산기의 전부이며, {@link ApproximateTokenCounter}
 * 의 상수가 바뀌어도 이 값은 움직이지 않는다.
 */
public class FixedTokenCounter implements TokenCounter {

	@Override
	public int count(String text) {
		if (text == null || text.isEmpty()) {
			return 0;
		}

		int wide = 0;
		int ascii = 0;
		for (int index = 0; index < text.length(); ) {
			int codePoint = text.codePointAt(index);
			index += Character.charCount(codePoint);
			if (codePoint > 0x7F) {
				wide++;
			}
			else {
				ascii++;
			}
		}
		return wide + Math.ceilDiv(ascii, 4);
	}
}

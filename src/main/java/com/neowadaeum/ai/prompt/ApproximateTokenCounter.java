package com.neowadaeum.ai.prompt;

/**
 * 벤더 없이 토큰 수를 <b>과대 추정</b>하는 계산기 (B-20, `[결정 필요]`).
 *
 * <p><b>원문이 세는 방법을 정하지 않았다.</b> §4.3 은 상한만 정하고 토큰화 방식은 어디에도 없다.
 * 토크나이저 라이브러리는 새 의존성이고, 무엇보다 <b>벤더마다 토큰화가 다르다</b> — 한 벤더의
 * 토크나이저를 붙이면 다른 벤더에서는 틀린 값이 된다.
 *
 * <p><b>그래서 방향을 정했다: 틀리더라도 많게 센다.</b> 과소 추정은 예산을 넘긴 요청을 보내 Provider
 * 오류와 비용을 낳고, 과대 추정은 컨텍스트를 조금 덜 싣는다. 되돌릴 수 없는 쪽은 전자다.
 *
 * <ul>
 *   <li>ASCII — 4자를 1토큰으로 본다
 *   <li>그 밖(한글·한자·이모지 등) — <b>글자마다 1토큰</b>으로 본다. 실제 한국어는 이보다 적게
 *       나오는 경우가 많고, 그 차이는 안전한 방향의 오차다
 * </ul>
 *
 * <p>실 Provider 가 붙으면 그 벤더의 계산으로 교체한다 (B-22). 이 클래스가 바뀌면 골든 파일이 아니라
 * <b>축소 시점</b>이 바뀌므로, 교체 시 예산 테스트를 함께 본다.
 */
public class ApproximateTokenCounter implements TokenCounter {

	private static final int ASCII_CHARS_PER_TOKEN = 4;

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

		return wide + Math.ceilDiv(ascii, ASCII_CHARS_PER_TOKEN);
	}
}

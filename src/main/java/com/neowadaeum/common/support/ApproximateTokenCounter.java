package com.neowadaeum.common.support;

/**
 * 벤더 없이 토큰 수를 <b>과대 추정</b>하는 계산기 (B-20, `[결정 필요]` §13-18).
 *
 * <p><b>원문이 세는 방법을 정하지 않았다.</b> §4.3 은 상한만 정하고 토큰화 방식은 어디에도 없다.
 * 토크나이저 라이브러리는 새 의존성이고, 무엇보다 <b>벤더마다 토큰화가 다르다.</b>
 *
 * <p><b>런타임 예산 판단은 언제나 이 로컬 계산이다</b> (#82). Provider 네이티브 카운팅에 기대지
 * 않는다 — 매 턴 네트워크 호출이 되고, 벤더가 둘이 되면 같은 작품이 벤더마다 다른 예산을 갖는다.
 * 실제 토큰 수는 응답의 {@code usage} 를 {@code ai_call_log} 에 남겨(B-11, B-25) 사후에 본다.
 * <b>계수를 고치는 근거는 그 관측이다</b> (B-48, B-46).
 *
 * <h2>계수 — 방향을 먼저 정한다</h2>
 *
 * <p>정확한 값은 벤더를 모르는 한 낼 수 없다. 대신 <b>틀리는 방향</b>을 고를 수 있다. 과소 추정은
 * 예산을 넘긴 요청을 실제로 보내 Provider 오류와 비용을 낳고, 과대 추정은 컨텍스트를 조금 덜
 * 싣는다. <b>되돌릴 수 없는 쪽은 전자다.</b>
 *
 * <table border="1">
 *   <caption>문자 분류별 계수</caption>
 *   <tr><th>분류</th><th>계수</th><th>근거</th></tr>
 *   <tr><td>한글·CJK</td><td>1.3 토큰/글자</td>
 *       <td><b>한국어를 과소 계산하지 않는 것이 이 계산기의 목적이다.</b> 현대 BPE 에서 한글은
 *           글자당 1 토큰 안팎이고 조사·어미에서 더 쪼개진다. 1.0 은 그 꼬리를 놓친다</td></tr>
 *   <tr><td>그 밖의 비ASCII</td><td>2.0 토큰/코드포인트</td>
 *       <td>이모지·기호는 여러 바이트로 쪼개져 가장 비싸게 나온다</td></tr>
 *   <tr><td>ASCII</td><td>0.25 토큰/글자 (4자에 1토큰)</td>
 *       <td>영문 BPE 의 통상값</td></tr>
 * </table>
 *
 * <p>그 위에 <b>안전 여유 {@value #SAFETY_MARGIN}</b> 를 곱한다. 계수는 평균에 대한 추정이고,
 * 예산은 <b>평균이 아니라 최악에서</b> 지켜져야 한다.
 */
public class ApproximateTokenCounter implements TokenCounter {

	/** 한글·CJK 한 글자의 추정 토큰. 1.0 은 조사·어미의 분할을 놓친다. */
	public static final double CJK_TOKENS_PER_CHAR = 1.3;

	/** 이모지·기호. 가장 비싸게 나오는 부류다. */
	public static final double OTHER_NON_ASCII_TOKENS_PER_CHAR = 2.0;

    /** ASCII 4자에 1토큰. */
	public static final double ASCII_TOKENS_PER_CHAR = 0.25;

	/** 안전 여유. 계수는 평균의 추정이고 예산은 최악에서 지켜져야 한다. */
	public static final double SAFETY_MARGIN = 1.1;

	@Override
	public int count(String text) {
		if (text == null || text.isEmpty()) {
			return 0;
		}

		double estimate = 0;
		for (int index = 0; index < text.length(); ) {
			int codePoint = text.codePointAt(index);
			index += Character.charCount(codePoint);
			estimate += tokensFor(codePoint);
		}

		// 올림이다. 0.4 토큰짜리 조각을 0 으로 세면 짧은 조각이 많을수록 과소해진다.
		return (int) Math.ceil(estimate * SAFETY_MARGIN);
	}

	private static double tokensFor(int codePoint) {
		if (codePoint <= 0x7F) {
			return ASCII_TOKENS_PER_CHAR;
		}
		return isCjk(codePoint) ? CJK_TOKENS_PER_CHAR : OTHER_NON_ASCII_TOKENS_PER_CHAR;
	}

	/** 한글(음절·자모·호환 자모)과 한자·가나. 서비스 언어가 한국어라 이 분류가 대부분을 차지한다. */
	private static boolean isCjk(int codePoint) {
		Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
		return script == Character.UnicodeScript.HANGUL
				|| script == Character.UnicodeScript.HAN
				|| script == Character.UnicodeScript.HIRAGANA
				|| script == Character.UnicodeScript.KATAKANA;
	}
}

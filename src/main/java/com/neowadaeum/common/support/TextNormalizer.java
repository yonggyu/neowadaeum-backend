package com.neowadaeum.common.support;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;

/**
 * 세이프티 대조용 텍스트 정규화기 (B-31, R2.5, R9.2).
 *
 * <p><b>R9.2 — 단순 문자열 매칭은 뚫린다.</b> 공백을 끼워 넣거나 글자를 비슷하게 생긴 숫자로
 * 바꾸거나 자모를 섞어 쓰면 같은 말이 다른 문자열이 된다. 그래서 대조 전에 <b>세 형태를 같은
 * 값으로 수렴</b>시킨다.
 *
 * <p><b>R2.5 — 조회는 항상 정규화된 값끼리 비교한다.</b> 한쪽만 정규화하면 대조가 성립하지 않는다.
 * 블록리스트의 {@code normalized_value} 도 이 함수를 거친 값이다.
 *
 * <p>단계
 *
 * <ol>
 *   <li><b>NFKC</b> — 전각·호환 문자를 표준형으로. 여기서 하지 않으면 이후 치환표가 전각 문자를 놓친다
 *   <li><b>소문자화</b> — 로캘 독립({@code Locale.ROOT}). 기본 로캘을 쓰면 서버 설정에 따라 결과가 달라진다
 *   <li><b>유사 문자 치환</b> — 생김새가 같은 숫자·라틴 문자를 대응하는 한글 자모로
 *   <li><b>잡문자 제거</b> — 공백 · 문장부호 · 폭 없는 문자. 글자와 숫자만 남긴다
 *   <li><b>NFKD</b> — 한글 음절을 첫소리·가운뎃소리·끝소리로 분해한다. 호환 자모도 같은 표현으로 모인다
 *   <li><b>음가 없는 첫소리 제거</b> — 아래 설명
 * </ol>
 *
 * <p><b>6단계가 수렴의 핵심이다.</b> 한글에서 첫소리 {@code ㅇ} 은 소리가 없다. 분해하면 모음
 * 하나짜리 글자와 {@code ㅇ + 모음} 글자가 서로 다른 자모열이 되는데, 유사 문자 치환은 자모 하나를
 * 만들어 내므로 그대로 두면 두 형태가 만나지 못한다. 첫소리 {@code ㅇ}(U+110B)만 지우면 만난다 —
 * 끝소리 {@code ㅇ}(U+11BC)은 소리가 있으므로 <b>지우지 않는다.</b>
 *
 * <p>과하게 수렴하면 서로 다른 말이 같은 값이 될 수 있다. 그 방향의 오류는 <b>차단이 늘어나는
 * 쪽</b>이며, 세이프티에서는 통과가 늘어나는 것보다 낫다.
 *
 * <p><b>S-11 — 이 파일에 블록리스트 항목이나 우회 표기 예시를 적지 않는다.</b> 아래 치환표는
 * 방어 규칙이지 우회 사례가 아니다. 실제 문자열은 테스트 픽스처에만 있다.
 */
public final class TextNormalizer {

	/** 음가 없는 첫소리 {@code ㅇ}. 끝소리 {@code ㅇ}(U+11BC)과 코드포인트가 다르다. */
	private static final char CHOSEONG_IEUNG = 'ᄋ';

	/**
	 * 생김새가 같은 문자를 한글 자모로 모은다.
	 *
	 * <p>값은 <b>호환 자모</b>가 아니라 조합용 자모다. 5단계의 NFKD 결과와 같은 표현이어야 만난다.
	 */
	private static final Map<Character, Character> LOOKALIKES = Map.of(
			'1', 'ᅵ',   // 가운뎃소리 ㅣ
			'l', 'ᅵ',
			'i', 'ᅵ',
			'0', 'ᄋ',   // 첫소리 ㅇ — 6단계에서 지워진다
			'o', 'ᄋ');

	private TextNormalizer() {
	}

	/**
	 * 대조용 정규화 값을 만든다.
	 *
	 * @param raw 원문. {@code null} 이면 빈 문자열을 돌려준다 — 대조 대상이 없다는 뜻이지
	 *            예외 상황이 아니다
	 */
	public static String normalize(String raw) {
		if (raw == null || raw.isEmpty()) {
			return "";
		}

		String compat = Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);

		StringBuilder substituted = new StringBuilder(compat.length());
		compat.chars().forEach(codePoint -> {
			char ch = (char) codePoint;
			substituted.append(LOOKALIKES.getOrDefault(ch, ch));
		});

		String decomposed = Normalizer.normalize(substituted, Normalizer.Form.NFKD);

		StringBuilder result = new StringBuilder(decomposed.length());
		decomposed.chars().forEach(codePoint -> {
			if (codePoint == CHOSEONG_IEUNG) {
				return;
			}
			if (Character.isLetterOrDigit(codePoint)) {
				result.appendCodePoint(codePoint);
			}
		});

		return result.toString();
	}
}

package com.neowadaeum.catalog.domain;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * 공개 표시명의 규칙 (이슈 #287, §13-7).
 *
 * <p><b>이 값은 사용자가 정하고 다른 사용자에게 보인다.</b> 그래서 길이·문자·정규화가
 * 표시 문제만이 아니다 — 규칙이 없으면 <b>같아 보이는 이름</b>과 <b>남을 사칭하는 이름</b>이
 * 만들어진다.
 *
 * <p><b>{@code @} 는 값에 들어가지 않는다.</b> 화면이 붙이는 표기이며(와이어프레임 3g 의
 * {@code @yeonwoo}), 값에 두면 {@code yeonwoo} 와 {@code @yeonwoo} 가 <b>서로 다른 행이면서
 * 같게 보인다.</b> 들어오면 조용히 벗기지 않고 <b>거절한다</b> — 벗기면 사용자가 무엇을 저장했는지
 * 모르게 되고, 정본이 서버와 화면 두 곳에 생긴다.
 *
 * <p><b>{@link com.neowadaeum.common.support.TextNormalizer} 와 목적이 다르다.</b> 그쪽은
 * 블록리스트 <b>대조</b>를 위해 서로 다른 표기를 한 값으로 수렴시키고, 여기서는 <b>저장하고
 * 보여 줄 값</b>을 정한다. 수렴한 값을 저장하면 사용자가 쓴 이름이 사라진다.
 */
public final class DisplayNames {

	/**
	 * <b>[결정 필요 → 확정 2026-09-02]</b> 길이 상한. 원문에 값이 없어 기본 채택안으로 정했다.
	 *
	 * <p>세는 단위는 <b>코드포인트</b>다. {@code String.length()} 로 세면 이모지·서로게이트 쌍이
	 * 한 글자를 둘로 세어 <b>같은 이름이 기준에 따라 길거나 짧아진다.</b>
	 */
	public static final int MIN_LENGTH = 2;

	public static final int MAX_LENGTH = 12;

	/**
	 * 허용 문자 — 한글 · 영문 · 숫자 · {@code _} · {@code -} · 공백.
	 *
	 * <p><b>허용목록이다.</b> 금지목록은 새 문자가 생길 때마다 뒤따라가야 하고, 뒤처진 그 순간이
	 * 빈틈이 된다. 한글은 완성형과 자모를 함께 받는다 — 조합 상태로 들어오는 입력기가 있다.
	 */
	private static final Pattern ALLOWED = Pattern
			.compile("[\\p{IsHangul}a-zA-Z0-9_\\- ]+");

	/**
	 * 거부할 문자 — 제어 · 폭 없는 문자 · 양방향 제어.
	 *
	 * <p>허용목록이 이미 막지만 <b>이유를 남기기 위해 따로 본다.</b> 이 문자들은 눈에 보이지
	 * 않으면서 <b>같아 보이는 다른 이름</b>을 만들거나 표시 순서를 뒤집는다 — 잘린 글자가 아니라
	 * 규칙으로 막아야 하는 것이다.
	 */
	private static final Pattern INVISIBLE = Pattern
			.compile("[\\p{Cntrl}\\p{Cf}\\u200B-\\u200F\\u2028-\\u202E\\u2060-\\u206F\\uFEFF]");

	/**
	 * 탈퇴 처리가 쓰는 이름. 사용자가 이것을 고르면 <b>탈퇴한 계정을 사칭한다.</b>
	 *
	 * <p><b>DB 제약으로 만들지 않는다.</b> 파기 배치가 이 값을 실제로 써야 하기 때문이다 —
	 * 제약을 걸면 그 배치가 막힌다. 막는 자리는 <b>사용자 입력이 들어오는 여기</b>다.
	 */
	private static final String RESERVED_WITHDRAWN = "탈퇴한 사용자";

	private DisplayNames() {
	}

	/**
	 * 정규화한 뒤 규칙을 확인한다.
	 *
	 * <p>순서가 규칙이다 — <b>정규화 먼저, 판정 나중.</b> 뒤집으면 양끝 공백이나 분해된 한글이
	 * 길이를 늘려 <b>보이는 것과 다른 이유로 거절된다.</b>
	 *
	 * @param raw 사용자가 입력한 값
	 * @return 저장할 값
	 * @throws IllegalArgumentException 규칙에 맞지 않는다
	 */
	public static String normalize(String raw) {
		if (raw == null) {
			throw new IllegalArgumentException("displayName is required");
		}
		// NFC — 자모로 분해돼 들어온 한글을 완성형으로 모은다. 같은 이름이 두 표현으로 저장되면
		// 중복 판정도 조회도 어긋난다.
		String value = Normalizer.normalize(raw, Normalizer.Form.NFC);

		if (INVISIBLE.matcher(value).find()) {
			throw new IllegalArgumentException("displayName has invisible or control characters");
		}
		// 양끝을 자르고 내부 연속 공백을 하나로. 공백만 다른 이름이 서로 달라 보이지 않게 한다.
		value = value.strip().replaceAll(" {2,}", " ");

		if (value.startsWith("@")) {
			// 화면이 붙이는 표기다. 벗기지 않고 거절한다 — 위 클래스 주석 참조.
			throw new IllegalArgumentException("displayName must not start with '@'");
		}
		int length = value.codePointCount(0, value.length());
		if (length < MIN_LENGTH || length > MAX_LENGTH) {
			throw new IllegalArgumentException("displayName length must be %d..%d".formatted(MIN_LENGTH, MAX_LENGTH));
		}
		if (!ALLOWED.matcher(value).matches()) {
			throw new IllegalArgumentException("displayName has characters that are not allowed");
		}
		if (RESERVED_WITHDRAWN.equals(value)) {
			throw new IllegalArgumentException("displayName is reserved");
		}
		return value;
	}
}

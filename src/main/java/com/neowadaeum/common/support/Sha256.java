package com.neowadaeum.common.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 원문을 남기지 않아야 하는 값의 해시 (§12 개인정보 최소화).
 *
 * <p>이메일과 IP 가 그 대상이다. <b>같은 사람인지 비교하는 데는 해시로 충분하고</b>, 원문이
 * 없으면 새어 나갈 값 자체가 존재하지 않는다 (I-3 를 구조로 보장하는 방법).
 *
 * <p>공통화한 이유는 <b>두 곳이 정확히 같은 연산</b>을 하기 때문이다. 대소문자·앞뒤 공백을
 * 정규화하지 않으면 같은 이메일이 다른 해시가 되고, 그 규칙이 한쪽에만 남는 날이 온다.
 */
public final class Sha256 {

	private Sha256() {
	}

	/**
	 * 소문자·트림 후 SHA-256 을 16진수로.
	 *
	 * @return 값이 비어 있으면 {@code null} — 없는 것과 빈 해시는 다르다
	 */
	public static String hex(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(value.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			// SHA-256 은 모든 JVM 에 있다. 없다면 해시 없이 진행하는 것보다 실패가 낫다.
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}
}

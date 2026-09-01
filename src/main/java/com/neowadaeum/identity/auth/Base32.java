package com.neowadaeum.identity.auth;

/**
 * Base32 인코딩 (RFC 4648, 패딩 없음).
 *
 * <p>인증기 앱에 비밀을 넘기는 표기다. JDK 에는 Base32 가 없고, 이것 하나 때문에 의존성을
 * 들이지 않는다 — 필요한 것은 <b>인코딩 한 방향</b>뿐이다.
 */
final class Base32 {

	private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

	private static final int BITS_PER_CHAR = 5;

	private Base32() {
	}

	static String encode(byte[] data) {
		StringBuilder encoded = new StringBuilder();
		int buffer = 0;
		int bits = 0;
		for (byte b : data) {
			buffer = (buffer << Byte.SIZE) | (b & 0xFF);
			bits += Byte.SIZE;
			while (bits >= BITS_PER_CHAR) {
				bits -= BITS_PER_CHAR;
				encoded.append(ALPHABET[(buffer >>> bits) & 0x1F]);
			}
		}
		if (bits > 0) {
			// 남은 비트는 왼쪽으로 밀어 한 글자를 채운다. 패딩은 붙이지 않는다 — 인증기 앱은
			// '=' 를 비밀의 일부로 읽는 경우가 있다.
			encoded.append(ALPHABET[(buffer << (BITS_PER_CHAR - bits)) & 0x1F]);
		}
		return encoded.toString();
	}
}

package com.neowadaeum.identity.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TOTP 코드 계산 (RFC 6238).
 *
 * <p><b>상태가 없다.</b> 저장·조회·정책 판단은 여기 없다 — 이 클래스는 "비밀과 시간이 주어지면
 * 코드는 무엇인가"만 답한다. 재사용 방지와 확인 여부는 그것을 아는 쪽(서비스)의 몫이다.
 *
 * <p>값은 §13-29 가 정한다 — HMAC-SHA1 · 6자리 · 30초 스텝. 인증기 앱의 사실상 표준이며,
 * 다르게 잡으면 <b>사용자가 가진 앱이 만드는 코드와 서버가 기대하는 코드가 어긋난다.</b>
 */
final class TotpCodes {

	/** 코드 하나가 유효한 시간 폭. 이 값이 곧 스텝의 크기다. */
	static final Duration STEP = Duration.ofSeconds(30);

	/** 자리 수. 여섯 자리이므로 값은 10^6 으로 자른다. */
	private static final int MODULO = 1_000_000;

	private static final String ALGORITHM = "HmacSHA1";

	private TotpCodes() {
	}

	/** 이 시각이 속한 스텝. 검증도 기록도 이 값으로 한다 — 초 단위로 다루면 재사용 판정이 어렵다. */
	static long stepAt(Instant instant) {
		return Math.floorDiv(instant.getEpochSecond(), STEP.getSeconds());
	}

	/**
	 * 해당 스텝의 코드.
	 *
	 * <p>앞자리 0 이 사라지면 인증기가 보여주는 것과 달라지므로 여섯 자리로 채운다.
	 */
	static String codeAt(byte[] secret, long step) {
		byte[] counter = new byte[Long.BYTES];
		for (int i = counter.length - 1; i >= 0; i--) {
			counter[i] = (byte) (step & 0xFF);
			step >>>= Byte.SIZE;
		}
		byte[] hash = hmac(secret, counter);

		// RFC 4226 §5.4 — 마지막 니블이 어디서부터 4바이트를 읽을지 가리킨다.
		int offset = hash[hash.length - 1] & 0x0F;
		int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
				| ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
		return "%06d".formatted(binary % MODULO);
	}

	/**
	 * 제출한 코드가 그 스텝의 코드인가.
	 *
	 * <p><b>{@link MessageDigest#isEqual} 로 비교한다.</b> {@code equals} 는 앞자리가 틀리면 즉시
	 * 끝나므로 <b>맞은 자리 수가 시간으로 새어 나간다</b> — 여섯 자리는 그 단서만으로도 좁혀진다.
	 */
	static boolean matches(byte[] secret, String submitted, long step) {
		if (submitted == null) {
			return false;
		}
		return MessageDigest.isEqual(codeAt(secret, step).getBytes(StandardCharsets.UTF_8),
				submitted.trim().getBytes(StandardCharsets.UTF_8));
	}

	private static byte[] hmac(byte[] secret, byte[] message) {
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(new SecretKeySpec(secret, ALGORITHM));
			return mac.doFinal(message);
		}
		catch (java.security.GeneralSecurityException ex) {
			// 알고리즘은 JDK 표준이고 키는 우리가 만든다. 여기 오면 설정이 아니라 환경의 문제다.
			throw new IllegalStateException("TOTP 코드를 계산하지 못했다", ex);
		}
	}
}

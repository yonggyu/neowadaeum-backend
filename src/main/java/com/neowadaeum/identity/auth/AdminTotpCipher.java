package com.neowadaeum.identity.auth;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.springframework.stereotype.Component;

/**
 * TOTP 비밀을 감싸고 푼다 (S-4).
 *
 * <p><b>AES-GCM 이다.</b> 감추기만 하는 방식으로는 저장된 암호문을 <b>고쳐 쓸 수</b> 있고, 그러면
 * 공격자가 자기 비밀을 심을 수 있다 — 인증 요소에서 그것은 암호화가 없는 것보다 나쁘다. GCM 은
 * 복호화 시점에 위변조를 함께 잡는다.
 *
 * <p><b>IV 는 매번 새로 만들어 암호문 앞에 붙인다.</b> 같은 IV 를 재사용하면 GCM 의 보증이
 * 통째로 무너진다. 난수는 I-15 의 금지 대상이 아니다 — 판정·분기·엔딩이 아니다.
 */
@Component
public class AdminTotpCipher {

	private static final String TRANSFORMATION = "AES/GCM/NoPadding";

	private static final int IV_BYTES = 12;

	private static final int TAG_BITS = 128;

	private final SecretKey key;

	private final SecureRandom random = new SecureRandom();

	public AdminTotpCipher(AdminTotpProperties properties) {
		this.key = properties.key();
	}

	/** 감싼다. 결과는 {@code base64(iv || 암호문)} 이며 그대로 한 컬럼에 들어간다. */
	public String wrap(byte[] secret) {
		byte[] iv = new byte[IV_BYTES];
		this.random.nextBytes(iv);
		byte[] sealed = run(Cipher.ENCRYPT_MODE, iv, secret, 0, secret.length);

		byte[] packed = new byte[iv.length + sealed.length];
		System.arraycopy(iv, 0, packed, 0, iv.length);
		System.arraycopy(sealed, 0, packed, iv.length, sealed.length);
		return Base64.getEncoder().encodeToString(packed);
	}

	/**
	 * 푼다.
	 *
	 * @throws IllegalStateException 위변조되었거나 다른 키로 감싼 값이다. <b>원문을 메시지에
	 *     싣지 않는다</b> (S-3)
	 */
	public byte[] unwrap(String wrapped) {
		byte[] packed = Base64.getDecoder().decode(wrapped);
		if (packed.length <= IV_BYTES) {
			throw new IllegalStateException("TOTP 비밀이 손상되었다");
		}
		byte[] iv = Arrays.copyOf(packed, IV_BYTES);
		return run(Cipher.DECRYPT_MODE, iv, packed, IV_BYTES, packed.length - IV_BYTES);
	}

	private byte[] run(int mode, byte[] iv, byte[] input, int offset, int length) {
		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(mode, this.key, new GCMParameterSpec(TAG_BITS, iv));
			return cipher.doFinal(input, offset, length);
		}
		catch (java.security.GeneralSecurityException ex) {
			throw new IllegalStateException("TOTP 비밀을 다루지 못했다", ex);
		}
	}
}

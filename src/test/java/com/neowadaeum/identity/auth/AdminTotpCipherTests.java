package com.neowadaeum.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * TOTP 비밀 감싸기 (S-4).
 *
 * <p>여기서 확인하는 것은 "되돌아오는가"만이 아니다. <b>고쳐 쓸 수 없는가</b>가 함께 걸려 있다 —
 * 저장된 암호문을 바꿔 치울 수 있으면 공격자가 자기 비밀을 심는다.
 */
class AdminTotpCipherTests {

	private static final byte[] SECRET = "비밀은 원문으로 남지 않는다".getBytes(StandardCharsets.UTF_8);

	@Test
	void SEC4_a_wrapped_secret_comes_back_unchanged() {
		AdminTotpCipher cipher = cipherWith(key(1));

		assertThat(cipher.unwrap(cipher.wrap(SECRET))).isEqualTo(SECRET);
	}

	/** <b>암호문에 원문이 비치지 않는다.</b> 저장된 값만 보고 비밀을 읽을 수 없어야 한다. */
	@Test
	void SEC4_the_wrapped_form_does_not_contain_the_secret() {
		String wrapped = cipherWith(key(1)).wrap(SECRET);

		assertThat(wrapped).doesNotContain(new String(SECRET, StandardCharsets.UTF_8));
	}

	/**
	 * <b>같은 비밀이 매번 다르게 감싸진다.</b> IV 를 재사용하면 GCM 의 보증이 무너지고, 같은
	 * 암호문이 나온다는 사실 자체가 "두 관리자가 같은 비밀을 쓴다"를 드러낸다.
	 */
	@Test
	void SEC4_the_same_secret_wraps_differently_every_time() {
		AdminTotpCipher cipher = cipherWith(key(1));

		assertThat(cipher.wrap(SECRET)).isNotEqualTo(cipher.wrap(SECRET));
	}

	/** 위변조는 <b>복호화에서 걸린다.</b> 감추기만 하는 방식이었다면 조용히 다른 값이 나온다. */
	@Test
	void SEC4_a_tampered_ciphertext_is_rejected() {
		AdminTotpCipher cipher = cipherWith(key(1));
		byte[] packed = Base64.getDecoder().decode(cipher.wrap(SECRET));
		packed[packed.length - 1] ^= 0x01;
		String tampered = Base64.getEncoder().encodeToString(packed);

		assertThatThrownBy(() -> cipher.unwrap(tampered)).isInstanceOf(IllegalStateException.class);
	}

	/** 다른 키로는 풀리지 않는다 — 키를 갈면 옛 등록은 못 쓰게 된다는 뜻이기도 하다. */
	@Test
	void SEC4_another_key_cannot_unwrap_it() {
		String wrapped = cipherWith(key(1)).wrap(SECRET);
		AdminTotpCipher other = cipherWith(key(2));

		assertThatThrownBy(() -> other.unwrap(wrapped)).isInstanceOf(IllegalStateException.class);
	}

	/** 잘려 나간 값도 거절한다. IV 만 남은 것을 복호화하려 들면 안 된다. */
	@Test
	void SEC4_a_truncated_value_is_rejected() {
		AdminTotpCipher cipher = cipherWith(key(1));

		assertThatThrownBy(() -> cipher.unwrap(Base64.getEncoder().encodeToString(new byte[8])))
				.isInstanceOf(IllegalStateException.class);
	}

	/** <b>키가 없으면 부팅이 실패한다</b> (§7.3). 형식이 어긋나도 마찬가지다. */
	@Test
	void SEC4_a_malformed_key_stops_the_boot() {
		assertThatThrownBy(() -> new AdminTotpProperties("not-base64!!").key())
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AdminTotpProperties(
				Base64.getEncoder().encodeToString(new byte[16])).key())
				.isInstanceOf(IllegalArgumentException.class);
	}

	/** <b>키 값이 예외 메시지에 실리지 않는다</b> (S-3). */
	@Test
	void SEC3_the_key_never_appears_in_the_failure_message() {
		String badKey = Base64.getEncoder().encodeToString(new byte[16]);

		assertThatThrownBy(() -> new AdminTotpProperties(badKey).key())
				.hasMessageNotContaining(badKey);
	}

	private AdminTotpCipher cipherWith(String base64Key) {
		return new AdminTotpCipher(new AdminTotpProperties(base64Key));
	}

	/** 테스트 키다. 어떤 실제 비밀도 감싸지 않는다 (S-11). */
	private String key(int seed) {
		byte[] material = new byte[32];
		java.util.Arrays.fill(material, (byte) seed);
		return Base64.getEncoder().encodeToString(material);
	}
}

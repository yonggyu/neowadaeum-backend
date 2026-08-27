package com.neowadaeum.identity.auth;

import jakarta.validation.constraints.NotBlank;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * TOTP 비밀을 감싸는 키 (B-40, S-4).
 *
 * <p><b>기본값을 두지 않는다</b> (§7.3). 값이 없으면 부팅이 실패한다 — 개발 편의로 기본 키를
 * 두면 <b>그 키로 감싼 비밀은 감싸지 않은 것과 같다.</b>
 *
 * <p>서명 키({@code auth.jwt.secret})와 나눈 이유는 용도가 다르기 때문이다. 서명 키는 위조를
 * 막고 이 키는 <b>읽히는 것</b>을 막는다 — 하나를 갈아야 할 때 다른 하나까지 갈면 그 사이 발급된
 * 모든 토큰이 함께 죽는다.
 */
@Validated
@ConfigurationProperties("admin.totp")
public record AdminTotpProperties(@NotBlank String secretKey) {

	/** AES-256. 키 길이를 옵션으로 두지 않는다 — 고를 이유가 없고, 고를 수 있으면 짧게 고른다. */
	private static final int KEY_BYTES = 32;

	/** 감싸기 키. 값이 base64 가 아니거나 길이가 다르면 <b>부팅에서 멈춘다.</b> */
	public SecretKey key() {
		byte[] decoded;
		try {
			decoded = Base64.getDecoder().decode(this.secretKey);
		}
		catch (IllegalArgumentException ex) {
			// 값을 메시지에 싣지 않는다 (S-3).
			throw new IllegalArgumentException("admin.totp.secret-key must be base64", ex);
		}
		if (decoded.length != KEY_BYTES) {
			throw new IllegalArgumentException(
					"admin.totp.secret-key must decode to %d bytes".formatted(KEY_BYTES));
		}
		return new SecretKeySpec(decoded, "AES");
	}
}

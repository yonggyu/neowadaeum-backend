package com.neowadaeum.identity.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 토큰 서명 설정 (B-12, §13.1).
 *
 * <p><b>기본값을 두지 않는다</b> (§7.3). {@code JWT_SECRET} 이 비어 있으면 부팅이 실패해야 한다 —
 * 개발 편의로 기본 시크릿을 두는 순간 그 값이 운영에 따라간다.
 *
 * <p><b>길이도 검사한다.</b> HS256 은 키가 짧아도 서명은 만들어 준다. 짧은 키는 오프라인
 * 무차별 대입으로 복구할 수 있고, 그러면 <b>누구든 임의의 {@code playerRef} 로 토큰을 만든다</b> —
 * #34 가 막으려던 것과 같은 상태다. 그래서 32바이트 미만이면 여기서 부팅을 세운다.
 */
@Validated
@ConfigurationProperties("auth.jwt")
public record JwtProperties(

		@NotBlank String secret,

		/** 액세스 토큰 수명. 짧을수록 탈취 창이 좁고, 짧을수록 재발급이 잦다. */
		@NotNull Duration accessTokenTtl,

		/** 리프레시 토큰 수명. 이 기간이 지나면 다시 로그인한다. */
		@NotNull Duration refreshTokenTtl,

		/**
		 * 관리자 단계 승격 수명 (B-40, S-4).
		 *
		 * <p><b>액세스 토큰보다 짧다.</b> 승격은 "방금 두 번째 요소를 통과했다"는 사실이며, 그
		 * 사실은 시간이 지나면 더 이상 참이 아니다 — 길게 잡으면 2FA 가 <b>로그인 때 한 번</b> 하는
		 * 절차가 되고, 자리를 비운 사이의 화면이 그대로 관리자 콘솔이 된다.
		 */
		@NotNull Duration adminStepUpTtl) {

	/** HS256 이 요구하는 최소 키 길이. 이보다 짧으면 서명 자체가 신뢰의 근거가 되지 못한다. */
	private static final int MINIMUM_SECRET_BYTES = 32;

	public JwtProperties {
		if (secret != null && secret.getBytes(StandardCharsets.UTF_8).length < MINIMUM_SECRET_BYTES) {
			// 값을 메시지에 싣지 않는다 (S-3). 길이만으로 원인이 충분히 드러난다.
			throw new IllegalArgumentException(
					"auth.jwt.secret must be at least %d bytes".formatted(MINIMUM_SECRET_BYTES));
		}
	}

	/** 서명 키. HMAC 이므로 발급과 검증이 같은 키를 쓴다. */
	public SecretKey signingKey() {
		return new SecretKeySpec(this.secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
	}
}

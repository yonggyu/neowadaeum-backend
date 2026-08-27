package com.neowadaeum.identity.auth;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

/**
 * 액세스·리프레시 토큰의 발급과 검증 (B-12, §13.1).
 *
 * <p><b>클레임에 회원 식별정보를 담지 않는다</b> (I-3). {@code sub} 는 {@code playerRef} 하나이며
 * 이메일·이름·생년월일·소셜 {@code subject} 는 들어가지 않는다. <b>JWT 는 서명될 뿐 암호화되지
 * 않는다</b> — 담은 것은 누구나 읽는다. 담지 않은 것만이 새지 않는다.
 *
 * <p><b>두 토큰을 {@code token_use} 로 구분한다.</b> 구분이 없으면 리프레시 토큰으로 보호 API 를
 * 부를 수 있고, 그러면 수명이 긴 토큰이 사실상 액세스 토큰이 된다 — 짧은 수명을 둔 이유가 사라진다.
 *
 * <p><b>리프레시는 상태를 두지 않는다.</b> 원문 어디에도 즉시 무효화 요구가 없고, 저장소를 두면
 * 모든 요청 경로에 조회가 하나 붙는다. 필요해지는 시점(강제 로그아웃·탈퇴 즉시 차단)은 B-61 ·
 * B-62 이며 그때 다시 정한다.
 *
 * <p>시각은 주입된 {@link Clock} 으로 본다. 만료 검증도 같은 시계를 쓰므로 테스트가 발급과 만료를
 * 함께 재현할 수 있다.
 */
@Service
public class AuthTokenService {

	/** 이 서버가 발급했음을 표시한다. 다른 발급자의 토큰이 섞여 들어오지 않게 한다. */
	static final String ISSUER = "neowadaeum";

	static final String TOKEN_USE_CLAIM = "token_use";

	static final String ACCESS = "access";

	static final String REFRESH = "refresh";

	private final JwtEncoder encoder;

	private final JwtDecoder decoder;

	private final JwtProperties properties;

	private final Clock clock;

	public AuthTokenService(JwtProperties properties, Clock clock) {
		SecretKey key = properties.signingKey();
		this.encoder = new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(key));
		NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withSecretKey(key)
				.macAlgorithm(MacAlgorithm.HS256)
				.build();
		// 시계 오차 허용을 두지 않는다. 발급자와 검증자가 같은 서버이므로 어긋날 여지가 없고,
		// 기본값 60초는 만료된 토큰이 1분 더 통하게 한다 — 짧은 수명을 둔 이유를 그만큼 깎는다.
		JwtTimestampValidator timestamps = new JwtTimestampValidator(java.time.Duration.ZERO);
		timestamps.setClock(clock);
		jwtDecoder.setJwtValidator(timestamps);
		this.decoder = jwtDecoder;
		this.properties = properties;
		this.clock = clock;
	}

	/** 로그인·재발급이 돌려주는 한 벌. 재발급도 두 토큰을 함께 갈아 끼운다(회전). */
	public AuthTokens issue(UUID playerRef) {
		Instant now = this.clock.instant();
		String access = sign(playerRef, ACCESS, now, this.properties.accessTokenTtl());
		String refresh = sign(playerRef, REFRESH, now, this.properties.refreshTokenTtl());
		return new AuthTokens(access, refresh, this.properties.accessTokenTtl().toSeconds());
	}

	/**
	 * 보호 API 가 부른다. 통과하면 요청자의 {@code playerRef} 다.
	 *
	 * @throws ApiException {@code UNAUTHENTICATED} — 위조·만료·용도 불일치를 구분하지 않는다.
	 *     구분해 알리면 공격자에게 어느 쪽이 맞았는지 알려주는 셈이다 (S-6)
	 */
	public UUID authenticate(String accessToken) {
		return subjectOf(accessToken, ACCESS);
	}

	/** {@code /auth/refresh} 가 부른다. <b>액세스 토큰으로는 통과하지 못한다.</b> */
	public UUID resolveRefresh(String refreshToken) {
		return subjectOf(refreshToken, REFRESH);
	}

	private String sign(UUID playerRef, String tokenUse, Instant now, java.time.Duration ttl) {
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(ISSUER)
				.subject(playerRef.toString())
				.issuedAt(now)
				.expiresAt(now.plus(ttl))
				// 같은 초에 두 번 발급해도 서로 다른 토큰이 된다. 나중에 무효화를 붙일 자리이기도 하다.
				.id(UUID.randomUUID().toString())
				.claim(TOKEN_USE_CLAIM, tokenUse)
				.build();
		return this.encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
	}

	private UUID subjectOf(String token, String expectedUse) {
		if (token == null || token.isBlank()) {
			throw new ApiException(ErrorCode.UNAUTHENTICATED);
		}
		Jwt jwt;
		try {
			jwt = this.decoder.decode(token);
		}
		catch (JwtException ex) {
			// 원문을 예외 메시지에 싣지 않는다 (S-3). 토큰은 그 자체가 자격 증명이다.
			throw new ApiException(ErrorCode.UNAUTHENTICATED, ex);
		}
		// getIssuer() 는 URL 로 변환하려 든다. 발급자는 URL 이 아니라 이름이므로 문자열로 읽는다.
		if (!ISSUER.equals(jwt.getClaimAsString("iss"))
				|| !expectedUse.equals(jwt.getClaimAsString(TOKEN_USE_CLAIM))) {
			throw new ApiException(ErrorCode.UNAUTHENTICATED);
		}
		try {
			return UUID.fromString(jwt.getSubject());
		}
		catch (IllegalArgumentException | NullPointerException ex) {
			throw new ApiException(ErrorCode.UNAUTHENTICATED, ex);
		}
	}
}

package com.neowadaeum.identity.auth;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * Google ID 토큰을 <b>서버가</b> 검증한다 (B-12, §13.1).
 *
 * <p><b>클라이언트가 보낸 토큰을 그대로 믿지 않는다.</b> 서명(구글 공개키) · 발급자 · 대상
 * ({@code aud}) · 만료 넷을 전부 본다. 하나라도 빠지면 남의 서비스용 토큰이나 만료된 토큰으로
 * 로그인이 된다.
 *
 * <p><b>이메일을 원문으로 내보내지 않는다.</b> 여기서 SHA-256 으로 바꾸고 원문은 이 클래스 밖으로
 * 나가지 않는다 (I-3, §12). 로그에도 남기지 않는다 (S-3).
 *
 * <p>공개키는 {@link NimbusJwtDecoder} 가 JWKS 에서 받아 캐시한다. <b>이 호출은 트랜잭션 밖에서
 * 일어나야 한다</b> — 회원 생성 트랜잭션 안에서 외부 HTTP 를 부르지 않는다(아키텍처 경계).
 */
@Component
public class GoogleIdTokenVerifier {

	/** 구글이 쓰는 두 표기. 둘 다 정상이며 하나만 받으면 어느 날 로그인이 전부 막힌다. */
	private static final Set<String> ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

	private final JwtDecoder decoder;

	private final String clientId;

	public GoogleIdTokenVerifier(GoogleOAuthProperties properties, Clock clock) {
		NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
		// 구글과 우리 서버의 시계는 다르다. 기본 허용치(60초)를 그대로 둔다 — 자체 발급
		// 토큰(AuthTokenService)과 달리 양쪽 시계를 우리가 맞출 수 없다.
		JwtTimestampValidator timestamps = new JwtTimestampValidator();
		timestamps.setClock(clock);
		jwtDecoder.setJwtValidator(timestamps);
		this.decoder = jwtDecoder;
		this.clientId = properties.clientId();
	}

	/**
	 * @return 검증을 통과한 계정. 이메일은 해시로만 담긴다
	 * @throws ApiException {@code UNAUTHENTICATED} — 어느 검사에서 걸렸는지 구분해 알리지 않는다 (S-6)
	 */
	public VerifiedSocialIdentity verify(String idToken) {
		if (idToken == null || idToken.isBlank()) {
			throw new ApiException(ErrorCode.UNAUTHENTICATED);
		}
		Jwt jwt;
		try {
			jwt = this.decoder.decode(idToken);
		}
		catch (JwtException ex) {
			// 토큰 원문을 예외에 싣지 않는다 (S-3). 그 자체가 자격 증명이다.
			throw new ApiException(ErrorCode.UNAUTHENTICATED, ex);
		}
		if (!ISSUERS.contains(jwt.getClaimAsString("iss")) || !addressedToUs(jwt)) {
			throw new ApiException(ErrorCode.UNAUTHENTICATED);
		}
		String subject = jwt.getSubject();
		if (subject == null || subject.isBlank()) {
			throw new ApiException(ErrorCode.UNAUTHENTICATED);
		}
		return new VerifiedSocialIdentity(subject, hashOf(jwt.getClaimAsString("email")));
	}

	/**
	 * <b>{@code aud} 가 우리 클라이언트인가.</b>
	 *
	 * <p>구글이 서명했다는 것만으로는 부족하다 — 다른 서비스용으로 발급된 토큰도 서명은 유효하다.
	 * 그 토큰을 받으면 <b>그 서비스의 사용자가 우리 서비스에 로그인할 수 있다.</b>
	 */
	private boolean addressedToUs(Jwt jwt) {
		List<String> audience = jwt.getAudience();
		return audience != null && audience.contains(this.clientId);
	}

	/** 이메일 원문을 저장하지 않는다. 같은 사람인지 비교하는 데는 해시로 충분하다 (§12). */
	private static String hashOf(String email) {
		if (email == null || email.isBlank()) {
			return null;
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(email.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			// SHA-256 은 모든 JVM 에 있다. 없다면 해시 없이 진행하는 것보다 실패가 낫다.
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}
}

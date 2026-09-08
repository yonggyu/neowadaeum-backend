package com.neowadaeum.identity.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * 구글을 대신하는 고정 응답 서버 <b>하나</b> — 통합 컨텍스트가 공유한다 (§13-11, 이슈 #431).
 *
 * <p><b>왜 필요한가.</b> 가입 왕복을 HTTP 로 돌리려면 서버가 실제로 검증하는 ID 토큰이 있어야
 * 하고, 그 토큰에는 이 서버가 방금 발급한 {@code nonce} 가 들어 있어야 한다 (§13-87). 그러려면
 * <b>테스트가 서명 키를 쥐어야</b> 한다 — 구글이 서명한 토큰을 얻는 방법은 구글을 부르는 것
 * 하나뿐이고, 그것은 하지 않는다 (테스트 규칙).
 *
 * <p><b>왜 {@code @MockitoBean} 이 아닌가.</b> 두 가지다. 검증기를 목으로 바꾸면 컨텍스트 캐시
 * 키가 갈라져 컨텍스트가 한 벌 더 뜨고({@link com.neowadaeum.ContainerTestBase}), 무엇보다
 * <b>검증기를 지나지 않는 로그인</b>을 보게 된다. 여기서 바꾸는 것은 JWKS 주소 하나이며
 * 서명·발급자·대상·만료 검증은 실물 그대로 돈다.
 *
 * <p><b>정적 서버 하나인 이유도 컨텍스트 1벌 규칙이다.</b> 주소는
 * {@link com.neowadaeum.TestcontainersConfiguration} 이 한 자리에서 프로퍼티로 주입한다 —
 * 테스트 클래스가 각자 {@code @DynamicPropertySource} 를 붙이면 그 수만큼 컨텍스트가 늘어난다.
 *
 * <p><b>키와 클라이언트 ID 는 테스트 전용이며 어떤 실제 계정도 가리키지 않는다</b> (S-11).
 * 키는 실행할 때마다 새로 만든다 — 소스에 서명 재료를 적지 않는다.
 *
 * <p>이것을 쓰는 곳은 {@code SignupRoundTripIntegrationTests} 다.
 */
public final class FakeGoogle {

	/** {@code aud} 검증이 대조하는 값. 설정과 토큰이 <b>같은 출처</b>를 봐야 한다. */
	public static final String CLIENT_ID = "test-only-client-id.apps.googleusercontent.com";

	/** 구글의 두 표기 중 하나. 둘 다 받는다는 것은 {@code GoogleIdTokenVerifierTests} 가 본다. */
	private static final String ISSUER = "https://accounts.google.com";

	private static final String JWKS_PATH = "/oauth2/v3/certs";

	private static final RSAKey KEY = generateKey();

	private static final WireMockServer SERVER = new WireMockServer(
			WireMockConfiguration.options().dynamicPort().http2PlainDisabled(true));

	static {
		SERVER.start();
		SERVER.stubFor(get(urlPathEqualTo(JWKS_PATH)).willReturn(aResponse()
				.withHeader("Content-Type", "application/json")
				.withBody(new JWKSet(KEY.toPublicJWK()).toString())));
	}

	private FakeGoogle() {
	}

	/** {@code security.oauth2.google.jwk-set-uri} 가 가리킬 주소. */
	public static String jwkSetUri() {
		return SERVER.baseUrl() + JWKS_PATH;
	}

	/**
	 * 이 서버가 서명한 ID 토큰.
	 *
	 * <p><b>{@code nonce} 는 클레임으로 들어간다</b> (§13-87) — 요청 본문 필드가 아니므로 값을
	 * 바꾸려면 토큰을 다시 만드는 수밖에 없다. 그 성질이 가입 왕복의 모양을 정한다: 2차 요청은
	 * <b>같은 토큰</b>을 그대로 다시 보낸다 (§13-88).
	 *
	 * @param subject 구글 계정 식별자. 테스트마다 다른 값을 주면 계정이 겹치지 않는다
	 * @param nonce   {@code POST /api/v1/auth/nonce} 가 방금 발급한 값. {@code null} 이면 싣지 않는다
	 */
	public static String idToken(String subject, String nonce) {
		Instant now = Instant.now();
		JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
				.issuer(ISSUER)
				.subject(subject)
				.audience(List.of(CLIENT_ID))
				// 문서용 도메인이다 (S-11). 검증기가 해시로 바꾸는 경로를 실제로 지난다 (I-3).
				.claim("email", subject + "@example.com")
				.issueTime(Date.from(now.minusSeconds(10)))
				.expirationTime(Date.from(now.plusSeconds(600)));
		if (nonce != null) {
			claims.claim("nonce", nonce);
		}
		return sign(claims.build());
	}

	private static String sign(JWTClaimsSet claims) {
		try {
			SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
					.keyID(KEY.getKeyID())
					.type(JOSEObjectType.JWT)
					.build(), claims);
			jwt.sign(new RSASSASigner(KEY));
			return jwt.serialize();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static RSAKey generateKey() {
		try {
			return new RSAKeyGenerator(2048).keyID("test-only-key-1").generate();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}
}

package com.neowadaeum.identity.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.neowadaeum.common.error.ApiException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * B-12(2/3) — <b>서버가 무엇을 믿고 무엇을 거부하는가</b> (§13.1).
 *
 * <p>구글을 부르지 않는다. 고정 응답 서버가 JWKS 를 대신하고, 테스트가 그 키로 토큰을 만든다 —
 * 그래야 <b>다른 키·다른 대상·다른 발급자</b>를 실제로 만들어 넣을 수 있다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class GoogleIdTokenVerifierTests {

	private static final String CLIENT_ID = "our-client-id.apps.googleusercontent.com";

	private static final String ISSUER = "https://accounts.google.com";

	private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

	private static final String JWKS_PATH = "/oauth2/v3/certs";

	private WireMockServer google;

	private RSAKey googleKey;

	private GoogleIdTokenVerifier verifier;

	@BeforeEach
	void startGoogle() throws Exception {
		this.googleKey = new RSAKeyGenerator(2048).keyID("google-key-1").generate();
		this.google = new WireMockServer(WireMockConfiguration.options().dynamicPort());
		this.google.start();
		this.google.stubFor(get(urlPathEqualTo(JWKS_PATH)).willReturn(aResponse()
				.withHeader("Content-Type", "application/json")
				.withBody(new JWKSet(this.googleKey.toPublicJWK()).toString())));
		this.verifier = new GoogleIdTokenVerifier(
				new GoogleOAuthProperties(CLIENT_ID, this.google.baseUrl() + JWKS_PATH),
				Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@AfterEach
	void stopGoogle() {
		this.google.stop();
	}

	/** 정상 토큰은 통과하고, 계정 식별자가 그대로 나온다. */
	@Test
	void S13_1_a_valid_id_token_yields_its_subject() {
		VerifiedSocialIdentity verified = this.verifier.verify(idToken(claims().build(), this.googleKey));

		assertThat(verified.subject()).isEqualTo("google-subject-1");
	}

	/**
	 * <b>이메일을 원문으로 내보내지 않는다</b> (I-3, §12).
	 *
	 * <p>해시가 원문과 다르다는 것만 보는 것으로는 부족하다 — <b>원문이 결과 어디에도 없어야</b> 한다.
	 */
	@Test
	void I3_the_email_never_leaves_the_verifier_in_the_clear() {
		String email = "player@example.com";

		VerifiedSocialIdentity verified = this.verifier.verify(
				idToken(claims().claim("email", email).build(), this.googleKey));

		assertThat(verified.emailHash()).isNotNull().hasSize(64).doesNotContain(email);
		assertThat(verified.toString()).doesNotContain(email);
	}

	/** 같은 이메일은 같은 해시다 — 대소문자·공백이 달라도 같은 사람이다. */
	@Test
	void the_email_hash_is_stable_across_case_and_padding() {
		String lower = this.verifier.verify(idToken(claims().claim("email", "a@b.com").build(), this.googleKey))
				.emailHash();
		String padded = this.verifier.verify(idToken(claims().claim("email", " A@B.com ").build(), this.googleKey))
				.emailHash();

		assertThat(lower).isEqualTo(padded);
	}

	/** 이메일이 없는 토큰도 정상이다. 해시는 비어 있다. */
	@Test
	void a_token_without_an_email_is_still_valid() {
		assertThat(this.verifier.verify(idToken(claims().build(), this.googleKey)).emailHash()).isNull();
	}

	/**
	 * <b>다른 서비스용으로 발급된 토큰은 거부된다.</b>
	 *
	 * <p>구글이 서명한 것은 맞다. 그러나 <b>우리에게 발급된 것이 아니다</b> — 받아 주면 그 서비스의
	 * 사용자가 우리 서비스에 로그인할 수 있다.
	 */
	@Test
	void S13_1_a_token_issued_for_another_audience_is_rejected() {
		String foreign = idToken(claims().audience(List.of("someone-else.apps.googleusercontent.com")).build(),
				this.googleKey);

		assertThatThrownBy(() -> this.verifier.verify(foreign)).isInstanceOf(ApiException.class);
	}

	/** 발급자가 구글이 아니면 거부된다. */
	@Test
	void S13_1_a_token_from_another_issuer_is_rejected() {
		String foreign = idToken(claims().issuer("https://accounts.example.com").build(), this.googleKey);

		assertThatThrownBy(() -> this.verifier.verify(foreign)).isInstanceOf(ApiException.class);
	}

	/** 구글의 두 표기를 모두 받는다. 하나만 받으면 어느 날 로그인이 전부 막힌다. */
	@Test
	void S13_1_both_google_issuer_spellings_are_accepted() {
		assertThat(this.verifier.verify(idToken(claims().issuer("accounts.google.com").build(), this.googleKey))
				.subject()).isEqualTo("google-subject-1");
	}

	/** 만료된 토큰은 거부된다. */
	@Test
	void S13_1_an_expired_id_token_is_rejected() {
		String expired = idToken(claims()
				.expirationTime(Date.from(NOW.minusSeconds(3600)))
				.build(), this.googleKey);

		assertThatThrownBy(() -> this.verifier.verify(expired)).isInstanceOf(ApiException.class);
	}

	/** 구글 키가 아닌 것으로 서명한 토큰은 거부된다 — JWKS 에 없는 키다. */
	@Test
	void S13_1_a_token_signed_with_an_unknown_key_is_rejected() throws Exception {
		RSAKey attacker = new RSAKeyGenerator(2048).keyID("google-key-1").generate();
		String forged = idToken(claims().build(), attacker);

		assertThatThrownBy(() -> this.verifier.verify(forged)).isInstanceOf(ApiException.class);
	}

	/** 빈 값·형식이 아닌 문자열도 401 이다. */
	@Test
	void a_missing_or_malformed_token_is_rejected() {
		assertThatThrownBy(() -> this.verifier.verify(null)).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> this.verifier.verify(" ")).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> this.verifier.verify("not.a.jwt")).isInstanceOf(ApiException.class);
	}

	private static JWTClaimsSet.Builder claims() {
		return new JWTClaimsSet.Builder()
				.issuer(ISSUER)
				.subject("google-subject-1")
				.audience(List.of(CLIENT_ID))
				.issueTime(Date.from(NOW.minusSeconds(10)))
				.expirationTime(Date.from(NOW.plusSeconds(3600)));
	}

	private static String idToken(JWTClaimsSet claims, RSAKey key) {
		try {
			SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
					.keyID(key.getKeyID())
					.type(JOSEObjectType.JWT)
					.build(), claims);
			jwt.sign(new RSASSASigner(key));
			return jwt.serialize();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}
}

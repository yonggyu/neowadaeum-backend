package com.neowadaeum.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * B-12(1/3) — 토큰이 <b>무엇을 담고 무엇을 거부하는가</b>.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001). 서명과 시계만 있으면 전부 재현된다.
 *
 * <p>시계를 주입받으므로 만료를 <b>기다리지 않고</b> 재현한다 — 테스트가 느려지는 대신
 * 시간에 의존하는 것이 가장 흔한 불안정 테스트의 원인이다.
 */
class AuthTokenServiceTests {

	private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

	private static final String SECRET = "test-only-jwt-signing-material-not-a-real-secret";

	private static final Duration ACCESS_TTL = Duration.ofMinutes(30);

	private static final Duration REFRESH_TTL = Duration.ofDays(30);

	/** 승격은 액세스보다 짧다 — 그것이 이 토큰을 따로 둔 이유다 (B-40, S-4). */
	private static final Duration STEP_UP_TTL = Duration.ofMinutes(15);

	private final UUID playerRef = UUID.randomUUID();

	private AuthTokenService serviceAt(Instant instant) {
		return new AuthTokenService(new JwtProperties(SECRET, ACCESS_TTL, REFRESH_TTL, STEP_UP_TTL),
				Clock.fixed(instant, ZoneOffset.UTC));
	}

	/** §13-22 — 계약이 정한 한 벌. {@code expiresIn} 은 액세스 토큰의 수명이다. */
	@Test
	void S13_22_issue_returns_the_pair_the_contract_declares() {
		AuthTokens tokens = serviceAt(NOW).issue(this.playerRef);

		assertThat(tokens.accessToken()).isNotBlank();
		assertThat(tokens.refreshToken()).isNotBlank();
		assertThat(tokens.expiresIn()).isEqualTo(ACCESS_TTL.toSeconds());
		assertThat(AuthTokens.TOKEN_TYPE).isEqualTo("Bearer");
	}

	/** 발급한 것을 다시 읽으면 같은 사람이다. */
	@Test
	void an_issued_access_token_resolves_back_to_its_player_ref() {
		AuthTokenService service = serviceAt(NOW);

		assertThat(service.authenticate(service.issue(this.playerRef).accessToken()))
				.isEqualTo(this.playerRef);
	}

	/**
	 * <b>I-3 — 토큰에 회원 식별정보가 없다.</b>
	 *
	 * <p><b>JWT 는 서명될 뿐 암호화되지 않는다.</b> 페이로드는 누구나 Base64 로 풀어 읽는다.
	 * 그러므로 "담았지만 안 보여 준다"는 성립하지 않고, <b>담지 않은 것만이 새지 않는다.</b>
	 * 페이로드를 실제로 풀어 클레임 이름을 확인한다 — 하나가 늘면 여기서 드러난다.
	 */
	@Test
	void I3_the_payload_carries_nothing_that_identifies_a_member() {
		String payload = decodePayload(serviceAt(NOW).issue(this.playerRef).accessToken());

		assertThat(payload).contains(this.playerRef.toString());
		assertThat(payload)
				.as("이메일·이름·생년월일·소셜 subject 는 토큰에 들어가지 않는다")
				.doesNotContain("email", "name", "birth", "sub_", "google");
	}

	/**
	 * <b>리프레시 토큰으로 보호 API 를 부를 수 없다.</b>
	 *
	 * <p>구분이 없으면 수명이 긴 토큰이 사실상 액세스 토큰이 되고, 짧은 수명을 둔 이유가 사라진다.
	 */
	@Test
	void a_refresh_token_is_not_accepted_as_an_access_token() {
		AuthTokenService service = serviceAt(NOW);
		AuthTokens tokens = service.issue(this.playerRef);

		assertThatThrownBy(() -> service.authenticate(tokens.refreshToken()))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.UNAUTHENTICATED);
	}

	/** 반대 방향도 막힌다 — 액세스 토큰으로 재발급받을 수 없다. */
	@Test
	void an_access_token_cannot_be_exchanged_for_new_tokens() {
		AuthTokenService service = serviceAt(NOW);
		AuthTokens tokens = service.issue(this.playerRef);

		assertThatThrownBy(() -> service.resolveRefresh(tokens.accessToken()))
				.isInstanceOf(ApiException.class);
	}

	/** 만료된 액세스 토큰은 401 이다. 같은 시계를 쓰므로 기다리지 않고 재현한다. */
	@Test
	void an_expired_access_token_is_rejected() {
		AuthTokens tokens = serviceAt(NOW).issue(this.playerRef);
		AuthTokenService later = serviceAt(NOW.plus(ACCESS_TTL).plusSeconds(1));

		assertThatThrownBy(() -> later.authenticate(tokens.accessToken()))
				.isInstanceOf(ApiException.class);
	}

	/** 액세스가 만료돼도 리프레시는 살아 있다. 그것이 두 수명을 나눈 이유다. */
	@Test
	void a_refresh_token_outlives_the_access_token() {
		AuthTokens tokens = serviceAt(NOW).issue(this.playerRef);
		AuthTokenService later = serviceAt(NOW.plus(ACCESS_TTL).plusSeconds(1));

		assertThat(later.resolveRefresh(tokens.refreshToken())).isEqualTo(this.playerRef);
	}

	/**
	 * <b>다른 키로 서명된 토큰은 통과하지 못한다.</b>
	 *
	 * <p>시크릿이 유출되면 누구나 임의의 {@code playerRef} 로 토큰을 만든다 — #34 가 막으려던
	 * 상태와 같다. 서명 검증이 그 마지막 방어선이다.
	 */
	@Test
	void a_token_signed_with_another_key_is_rejected() {
		AuthTokens forged = new AuthTokenService(
				new JwtProperties("another-secret-that-is-long-enough-to-sign", ACCESS_TTL, REFRESH_TTL,
						STEP_UP_TTL),
				Clock.fixed(NOW, ZoneOffset.UTC)).issue(this.playerRef);

		assertThatThrownBy(() -> serviceAt(NOW).authenticate(forged.accessToken()))
				.isInstanceOf(ApiException.class);
	}

	/** 빈 값·형식이 아닌 문자열도 401 이다. 어느 쪽이 틀렸는지 알려주지 않는다 (S-6). */
	@Test
	void a_malformed_or_missing_token_is_rejected() {
		AuthTokenService service = serviceAt(NOW);

		assertThatThrownBy(() -> service.authenticate(null)).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> service.authenticate("  ")).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> service.authenticate("not.a.jwt")).isInstanceOf(ApiException.class);
	}

	/**
	 * <b>§7.3 — 짧은 시크릿이면 부팅이 실패한다.</b>
	 *
	 * <p>플레이스홀더가 해석되지 않은 채 남으면 {@code "${JWT_SECRET}"} 이라는 14바이트 문자열이
	 * 그대로 들어온다. 그 상태로 뜨면 <b>추측 가능한 키로 서명이 돌아간다.</b>
	 */
	@Test
	void S7_3_a_short_secret_fails_fast() {
		assertThatThrownBy(() -> new JwtProperties("${JWT_SECRET}", ACCESS_TTL, REFRESH_TTL, STEP_UP_TTL))
				.isInstanceOf(IllegalArgumentException.class)
				.as("메시지에 값 자체를 싣지 않는다 (S-3)")
				.hasMessageNotContaining("${JWT_SECRET}");
	}

	private static String decodePayload(String jwt) {
		String[] parts = jwt.split("\\.");
		assertThat(parts).hasSize(3);
		return new String(Base64.getUrlDecoder().decode(parts[1]));
	}

	/**
	 * <b>승격은 별개의 용도다</b> (B-40, S-4).
	 *
	 * <p>액세스 토큰으로 관리자 문이 열리면 2FA 를 둔 이유가 사라진다 — 로그인 하나로 끝난다.
	 */
	@Test
	void SEC4_an_access_token_does_not_resolve_as_a_step_up() {
		AuthTokenService tokens = serviceAt(NOW);

		assertThatThrownBy(() -> tokens.resolveAdminStepUp(tokens.issue(this.playerRef).accessToken()))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.UNAUTHENTICATED);
	}

	/** 반대도 같다 — 승격 토큰이 일반 API 를 여는 만능 열쇠가 되면 안 된다. */
	@Test
	void SEC4_a_step_up_does_not_authenticate_ordinary_requests() {
		AuthTokenService tokens = serviceAt(NOW);
		String stepUp = tokens.issueAdminStepUp(this.playerRef).token();

		assertThatThrownBy(() -> tokens.authenticate(stepUp)).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> tokens.resolveRefresh(stepUp)).isInstanceOf(ApiException.class);
	}

	/** 승격의 주인은 발급받은 그 사람이다. */
	@Test
	void SEC4_a_step_up_resolves_to_its_owner() {
		AuthTokenService tokens = serviceAt(NOW);

		assertThat(tokens.resolveAdminStepUp(tokens.issueAdminStepUp(this.playerRef).token()))
				.isEqualTo(this.playerRef);
	}

	/** <b>수명이 지나면 더 이상 참이 아니다.</b> 승격은 "방금 통과했다"는 사실이다. */
	@Test
	void SEC4_a_step_up_expires() {
		String stepUp = serviceAt(NOW).issueAdminStepUp(this.playerRef).token();
		AuthTokenService later = serviceAt(NOW.plus(STEP_UP_TTL).plusSeconds(1));

		assertThatThrownBy(() -> later.resolveAdminStepUp(stepUp)).isInstanceOf(ApiException.class);
	}

	/** 클라이언트가 <b>언제 다시 코드를 물어야 하는지</b> 알아야 한다. */
	@Test
	void SEC4_a_step_up_reports_its_lifetime() {
		assertThat(serviceAt(NOW).issueAdminStepUp(this.playerRef).expiresIn())
				.isEqualTo(STEP_UP_TTL.toSeconds());
	}
}

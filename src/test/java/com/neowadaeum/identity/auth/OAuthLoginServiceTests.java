package com.neowadaeum.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.identity.domain.ConsentLog;
import com.neowadaeum.identity.domain.ConsentType;
import com.neowadaeum.identity.domain.OauthIdentity;
import com.neowadaeum.identity.domain.OauthProvider;
import com.neowadaeum.identity.domain.User;
import com.neowadaeum.identity.domain.UserStatus;
import com.neowadaeum.identity.repository.ConsentLogRepository;
import com.neowadaeum.identity.repository.OauthIdentityRepository;
import com.neowadaeum.identity.repository.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * B-12(2/3) — 로그인 유스케이스의 성질 (§13.1).
 *
 * <p>구글도 DB 도 부르지 않는다. 여기서 보는 것은 <b>어떤 순서로 무엇이 일어나는가</b>다 —
 * 검증이 먼저이고, 회원 생성은 그다음이며, 정지 회원은 토큰을 받지 못한다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class OAuthLoginServiceTests {

	private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

	private static final VerifiedSocialIdentity VERIFIED =
			new VerifiedSocialIdentity("google-subject-1", "email-hash");

	private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

	private final AuthTokenService tokens = new AuthTokenService(
			new JwtProperties("test-only-jwt-signing-material-not-a-real-secret",
					Duration.ofMinutes(30), Duration.ofDays(30), Duration.ofMinutes(15)),
			this.clock);

	private final GoogleIdTokenVerifier verifier = mock(GoogleIdTokenVerifier.class);

	private final UserRepository users = mock(UserRepository.class);

	private final OauthIdentityRepository links = mock(OauthIdentityRepository.class);

	private final ConsentLogRepository consentLogs = mock(ConsentLogRepository.class);

	private final SocialAccountRegistrar registrar =
			new SocialAccountRegistrar(this.users, this.links, this.consentLogs, this.clock);

	private final OAuthLoginService service =
			new OAuthLoginService(this.verifier, this.registrar, this.tokens, new AgeGate(this.clock));

	/** 만 15세를 넘긴 생년월일. 경계값은 {@code AgeGateTests} 가 따로 본다. */
	private static final LocalDate ADULT_ENOUGH = LocalDate.of(2005, 1, 1);

	private static SignupInfo signup() {
		return new SignupInfo(ADULT_ENOUGH, List.of(
				new SignupInfo.ConsentDecision(ConsentType.TOS, "v1", true),
				new SignupInfo.ConsentDecision(ConsentType.PRIVACY, "v1", true),
				new SignupInfo.ConsentDecision(ConsentType.AI_NOTICE, "v1", true)));
	}

	/** 최초 로그인이면 회원과 연결이 함께 생긴다 (§13.1). */
	@Test
	void S13_1_a_first_login_creates_the_user_and_the_link() {
		given(this.verifier.verify("id-token")).willReturn(VERIFIED);
		given(this.links.findByProviderAndSubject(OauthProvider.GOOGLE, "google-subject-1"))
				.willReturn(Optional.empty());
		// JPA 가 없으므로 id 를 대신 채운다. 실제로는 save() 가 돌려주는 엔티티에 이미 있다.
		given(this.users.save(any(User.class)))
				.willAnswer(invocation -> withField(invocation.getArgument(0), "id", UUID.randomUUID()));

		AuthTokens issued = this.service.login(OauthProvider.GOOGLE, "id-token", signup(), "ip-hash");

		assertThat(this.tokens.authenticate(issued.accessToken())).isNotNull();
		verify(this.users).save(any(User.class));
		verify(this.links).save(any(OauthIdentity.class));
	}

	/**
	 * <b>기존 회원이면 새로 만들지 않는다.</b>
	 *
	 * <p>만들면 같은 사람이 로그인할 때마다 {@code playerRef} 가 바뀌고, 그 순간 지난 세션과
	 * 작품이 전부 남의 것이 된다 (§2.1).
	 */
	@Test
	void S2_1_a_returning_login_reuses_the_same_player_ref() {
		UUID userId = UUID.randomUUID();
		UUID playerRef = UUID.randomUUID();
		given(this.verifier.verify("id-token")).willReturn(VERIFIED);
		given(this.links.findByProviderAndSubject(OauthProvider.GOOGLE, "google-subject-1"))
				.willReturn(Optional.of(OauthIdentity.link(userId, OauthProvider.GOOGLE, "google-subject-1",
						null, NOW)));
		given(this.users.findById(userId)).willReturn(Optional.of(User.register(playerRef, null, NOW)));

		AuthTokens issued = this.service.login(OauthProvider.GOOGLE, "id-token", signup(), "ip-hash");

		assertThat(this.tokens.authenticate(issued.accessToken())).isEqualTo(playerRef);
		verify(this.users, never()).save(any(User.class));
	}

	/**
	 * <b>검증에 실패하면 아무것도 저장하지 않는다.</b>
	 *
	 * <p>순서가 뒤집히면 위조 토큰 하나로 회원이 생긴다.
	 */
	@Test
	void S13_1_a_rejected_id_token_creates_nothing() {
		given(this.verifier.verify("bad")).willThrow(new ApiException(ErrorCode.UNAUTHENTICATED));

		assertThatThrownBy(() -> this.service.login(OauthProvider.GOOGLE, "bad", signup(), "ip-hash"))
				.isInstanceOf(ApiException.class);
		verify(this.users, never()).save(any(User.class));
		verify(this.links, never()).save(any(OauthIdentity.class));
	}

	/** MVP 는 구글 하나다 (§13-11). 값이 enum 에 있는 것과 경로가 열린 것은 다르다. */
	@Test
	void S13_11_a_provider_outside_the_mvp_is_rejected_before_anything_else() {
		assertThatThrownBy(() -> this.service.login(OauthProvider.APPLE, "id-token", signup(), "ip-hash"))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR);
		verify(this.verifier, never()).verify(any());
	}

	/** 정지 회원은 토큰을 받지 못한다. 여기서 막지 않으면 뒤의 모든 경로가 각자 확인해야 한다. */
	@Test
	void a_suspended_member_cannot_obtain_tokens() {
		UUID userId = UUID.randomUUID();
		User suspended = suspended();
		given(this.verifier.verify("id-token")).willReturn(VERIFIED);
		given(this.links.findByProviderAndSubject(eq(OauthProvider.GOOGLE), eq("google-subject-1")))
				.willReturn(Optional.of(OauthIdentity.link(userId, OauthProvider.GOOGLE, "google-subject-1",
						null, NOW)));
		given(this.users.findById(userId)).willReturn(Optional.of(suspended));

		assertThatThrownBy(() -> this.service.login(OauthProvider.GOOGLE, "id-token", signup(), "ip-hash"))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.FORBIDDEN);
	}

	/**
	 * <b>가입 정보가 없으면 계정이 만들어지지 않는다</b> (§4.1, B-13).
	 *
	 * <p>기존 회원이 아닌데 생년월일이 없으면 <b>나이를 확인할 방법이 없다.</b> 그 상태로 계정을
	 * 만들면 확인받지 않은 회원이 남는다.
	 */
	@Test
	void S4_1_a_first_login_without_signup_information_creates_nothing() {
		givenNewAccount();

		assertThatThrownBy(() -> this.service.login(OauthProvider.GOOGLE, "id-token",
				new SignupInfo(null, List.of()), "ip-hash"))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.CONSENT_REQUIRED);
		verify(this.users, never()).save(any(User.class));
		verify(this.links, never()).save(any(OauthIdentity.class));
	}

	/** 필수 동의 하나가 빠져도 같다 (§4.1 — 약관·개인정보·AI고지). */
	@Test
	void S4_1_a_missing_required_consent_is_the_same_as_none() {
		givenNewAccount();
		SignupInfo partial = new SignupInfo(ADULT_ENOUGH, List.of(
				new SignupInfo.ConsentDecision(ConsentType.TOS, "v1", true),
				new SignupInfo.ConsentDecision(ConsentType.PRIVACY, "v1", true)));

		assertThatThrownBy(() -> this.service.login(OauthProvider.GOOGLE, "id-token", partial, "ip-hash"))
				.isInstanceOf(ApiException.class);
		verify(this.users, never()).save(any(User.class));
	}

	/** 체크를 해제한 필수 동의는 보내지 않은 것과 같다. */
	@Test
	void S4_1_an_unchecked_required_consent_is_the_same_as_missing() {
		givenNewAccount();
		SignupInfo declined = new SignupInfo(ADULT_ENOUGH, List.of(
				new SignupInfo.ConsentDecision(ConsentType.TOS, "v1", true),
				new SignupInfo.ConsentDecision(ConsentType.PRIVACY, "v1", true),
				new SignupInfo.ConsentDecision(ConsentType.AI_NOTICE, "v1", false)));

		assertThatThrownBy(() -> this.service.login(OauthProvider.GOOGLE, "id-token", declined, "ip-hash"))
				.isInstanceOf(ApiException.class);
		verify(this.users, never()).save(any(User.class));
	}

	/**
	 * <b>만 15세 미만이면 계정을 만들지 않는다</b> (R10.2).
	 *
	 * <p>거부하고 계정만 남기면 그 계정은 나이를 확인받지 않은 채 존재하게 된다.
	 * 경계값 자체는 {@code AgeGateTests} 가 본다.
	 */
	@Test
	void R10_2_an_underage_signup_creates_neither_user_nor_link() {
		givenNewAccount();
		SignupInfo tooYoung = new SignupInfo(LocalDate.of(2020, 1, 1), signup().consents());

		assertThatThrownBy(() -> this.service.login(OauthProvider.GOOGLE, "id-token", tooYoung, "ip-hash"))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.AGE_RESTRICTED);
		verify(this.users, never()).save(any(User.class));
		verify(this.links, never()).save(any(OauthIdentity.class));
		verify(this.consentLogs, never()).save(any(ConsentLog.class));
	}

	/**
	 * <b>동의가 판본과 함께 남고, 서버가 연령 확인 사실을 스스로 기록한다</b> (R10.2).
	 *
	 * <p>{@code age} 는 화면의 체크박스가 아니다 — 사용자가 "만 15세 이상입니다"에 체크했다는
	 * 사실보다 <b>서버가 생년월일로 확인했다는 사실</b>이 증빙이다.
	 */
	@Test
	void R10_2_signup_records_every_consent_plus_the_server_side_age_check() {
		givenNewAccount();

		this.service.login(OauthProvider.GOOGLE, "id-token", signup(), "ip-hash");

		ArgumentCaptor<ConsentLog> saved = ArgumentCaptor.forClass(ConsentLog.class);
		verify(this.consentLogs, times(4)).save(saved.capture());
		assertThat(saved.getAllValues()).extracting(ConsentLog::getConsentType)
				.containsExactlyInAnyOrder(ConsentType.TOS, ConsentType.PRIVACY, ConsentType.AI_NOTICE,
						ConsentType.AGE);
		assertThat(saved.getAllValues()).allSatisfy(log -> {
			assertThat(log.getVersion()).isNotBlank();
			assertThat(log.getIpHash()).as("IP 는 해시로만 남는다 (§12)").isEqualTo("ip-hash");
		});
	}

	/**
	 * <b>기존 회원은 가입 정보 없이 로그인한다.</b>
	 *
	 * <p>로그인할 때마다 동의를 다시 받으면 동의 이력이 로그인 이력이 되고, "언제 무엇에
	 * 동의했는가"가 흐려진다.
	 */
	@Test
	void S4_1_a_returning_member_needs_no_signup_information() {
		UUID userId = UUID.randomUUID();
		UUID playerRef = UUID.randomUUID();
		given(this.verifier.verify("id-token")).willReturn(VERIFIED);
		given(this.links.findByProviderAndSubject(OauthProvider.GOOGLE, "google-subject-1"))
				.willReturn(Optional.of(OauthIdentity.link(userId, OauthProvider.GOOGLE, "google-subject-1",
						null, NOW)));
		given(this.users.findById(userId)).willReturn(Optional.of(User.register(playerRef, null, NOW)));

		AuthTokens issued = this.service.login(OauthProvider.GOOGLE, "id-token",
				new SignupInfo(null, List.of()), "ip-hash");

		assertThat(this.tokens.authenticate(issued.accessToken())).isEqualTo(playerRef);
		verify(this.consentLogs, never()).save(any(ConsentLog.class));
	}

	/** 최초 로그인 상태를 만든다 — 연결이 없고, 저장은 id 를 채워 돌려준다. */
	private void givenNewAccount() {
		given(this.verifier.verify("id-token")).willReturn(VERIFIED);
		given(this.links.findByProviderAndSubject(OauthProvider.GOOGLE, "google-subject-1"))
				.willReturn(Optional.empty());
		given(this.users.save(any(User.class)))
				.willAnswer(invocation -> withField(invocation.getArgument(0), "id", UUID.randomUUID()));
	}

	/** 재발급은 저장소를 건드리지 않는다 — 상태 없는 리프레시의 실질이다. */
	@Test
	void refresh_rotates_without_touching_the_store() {
		UUID playerRef = UUID.randomUUID();
		AuthTokens first = this.tokens.issue(playerRef);

		AuthTokens rotated = this.service.refresh(first.refreshToken());

		assertThat(this.tokens.authenticate(rotated.accessToken())).isEqualTo(playerRef);
		verify(this.users, never()).findById(any());
	}

	/** 정지 상태를 만들 공개 경로가 아직 없다 — B-40 이다. 그 상태만 재현한다. */
	private static User suspended() {
		return withField(User.register(UUID.randomUUID(), null, NOW), "status", UserStatus.SUSPENDED);
	}

	/**
	 * 영속화가 채워 줄 값을 테스트에서 대신 넣는다.
	 *
	 * <p><b>프로덕션 코드에 테스트용 세터를 만들지 않기 위해서다.</b> 세터를 두면 그 경로가
	 * 언젠가 실제 코드에서 쓰이고, 그때 엔티티의 불변성이 조용히 사라진다.
	 */
	private static User withField(User user, String name, Object value) {
		try {
			java.lang.reflect.Field field = User.class.getDeclaredField(name);
			field.setAccessible(true);
			field.set(user, value);
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
		return user;
	}
}

package com.neowadaeum.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.identity.domain.OauthIdentity;
import com.neowadaeum.identity.domain.OauthProvider;
import com.neowadaeum.identity.domain.User;
import com.neowadaeum.identity.domain.UserStatus;
import com.neowadaeum.identity.repository.OauthIdentityRepository;
import com.neowadaeum.identity.repository.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

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
					Duration.ofMinutes(30), Duration.ofDays(30)),
			this.clock);

	private final GoogleIdTokenVerifier verifier = mock(GoogleIdTokenVerifier.class);

	private final UserRepository users = mock(UserRepository.class);

	private final OauthIdentityRepository links = mock(OauthIdentityRepository.class);

	private final SocialAccountRegistrar registrar = new SocialAccountRegistrar(this.users, this.links, this.clock);

	private final OAuthLoginService service = new OAuthLoginService(this.verifier, this.registrar, this.tokens);

	/** 최초 로그인이면 회원과 연결이 함께 생긴다 (§13.1). */
	@Test
	void S13_1_a_first_login_creates_the_user_and_the_link() {
		given(this.verifier.verify("id-token")).willReturn(VERIFIED);
		given(this.links.findByProviderAndSubject(OauthProvider.GOOGLE, "google-subject-1"))
				.willReturn(Optional.empty());
		// JPA 가 없으므로 id 를 대신 채운다. 실제로는 save() 가 돌려주는 엔티티에 이미 있다.
		given(this.users.save(any(User.class)))
				.willAnswer(invocation -> withField(invocation.getArgument(0), "id", UUID.randomUUID()));

		AuthTokens issued = this.service.login(OauthProvider.GOOGLE, "id-token");

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

		AuthTokens issued = this.service.login(OauthProvider.GOOGLE, "id-token");

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

		assertThatThrownBy(() -> this.service.login(OauthProvider.GOOGLE, "bad"))
				.isInstanceOf(ApiException.class);
		verify(this.users, never()).save(any(User.class));
		verify(this.links, never()).save(any(OauthIdentity.class));
	}

	/** MVP 는 구글 하나다 (§13-11). 값이 enum 에 있는 것과 경로가 열린 것은 다르다. */
	@Test
	void S13_11_a_provider_outside_the_mvp_is_rejected_before_anything_else() {
		assertThatThrownBy(() -> this.service.login(OauthProvider.APPLE, "id-token"))
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

		assertThatThrownBy(() -> this.service.login(OauthProvider.GOOGLE, "id-token"))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.FORBIDDEN);
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

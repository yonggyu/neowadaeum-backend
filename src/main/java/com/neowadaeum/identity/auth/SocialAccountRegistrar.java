package com.neowadaeum.identity.auth;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.identity.domain.OauthIdentity;
import com.neowadaeum.identity.domain.OauthProvider;
import com.neowadaeum.identity.domain.User;
import com.neowadaeum.identity.domain.UserStatus;
import com.neowadaeum.identity.repository.OauthIdentityRepository;
import com.neowadaeum.identity.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 검증된 소셜 계정을 회원에 잇는다 — <b>트랜잭션은 여기까지다</b> (B-12).
 *
 * <p><b>별도 빈인 것은 의도다.</b> 같은 클래스 안에서 {@code @Transactional} 메서드를 부르면
 * 프록시를 지나지 않아 <b>트랜잭션이 걸리지 않는다.</b> 경계를 빈으로 나누면 그 함정이 없고,
 * 동시에 "외부 호출은 밖, 저장은 안"이라는 구분이 타입으로 드러난다.
 *
 * <p>매니저를 <b>명시</b>한다. 후보가 넷이므로 이름 없는 {@code @Transactional} 은 부팅에서 실패한다.
 */
@Component
public class SocialAccountRegistrar {

	private final UserRepository users;

	private final OauthIdentityRepository oauthIdentities;

	private final Clock clock;

	public SocialAccountRegistrar(UserRepository users, OauthIdentityRepository oauthIdentities, Clock clock) {
		this.users = users;
		this.oauthIdentities = oauthIdentities;
		this.clock = clock;
	}

	/**
	 * 최초면 회원과 연결을 만들고, 아니면 기존 회원을 찾는다.
	 *
	 * <p>{@code (provider, subject)} 가 UNIQUE 이므로 동시에 두 요청이 들어와도 둘 다 만들지는
	 * 못한다 — 늦은 쪽은 제약 위반으로 실패한다. 그 상태를 조용히 흡수하지 않는다.
	 *
	 * @return 그 회원의 {@code playerRef}. <b>{@code user.id} 는 identity 밖으로 나가지 않는다</b> (I-3)
	 */
	@Transactional("identityTransactionManager")
	public UUID linkOrCreate(OauthProvider provider, VerifiedSocialIdentity verified) {
		Instant now = this.clock.instant();
		return this.oauthIdentities.findByProviderAndSubject(provider, verified.subject())
				.map(link -> playerRefOf(link.getUserId()))
				.orElseGet(() -> create(provider, verified, now));
	}

	private UUID create(OauthProvider provider, VerifiedSocialIdentity verified, Instant now) {
		// 연령 게이트와 동의 기록은 B-13 이다. 그래서 birthDate 가 아직 비어 있다.
		User user = this.users.save(User.register(UUID.randomUUID(), null, now));
		this.oauthIdentities.save(
				OauthIdentity.link(user.getId(), provider, verified.subject(), verified.emailHash(), now));
		return user.getPlayerRef();
	}

	/**
	 * <b>정지·탈퇴 회원은 토큰을 받지 못한다.</b>
	 *
	 * <p>로그인 시점에 막지 않으면 그 뒤의 모든 경로가 각자 상태를 확인해야 한다.
	 */
	private UUID playerRefOf(UUID userId) {
		User user = this.users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
		if (user.getStatus() != UserStatus.ACTIVE) {
			throw new ApiException(ErrorCode.FORBIDDEN);
		}
		return user.getPlayerRef();
	}
}

package com.neowadaeum.identity.auth;

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
import java.time.Instant;
import java.util.Optional;
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

	private final ConsentLogRepository consents;

	private final Clock clock;

	public SocialAccountRegistrar(UserRepository users, OauthIdentityRepository oauthIdentities,
			ConsentLogRepository consents, Clock clock) {
		this.users = users;
		this.oauthIdentities = oauthIdentities;
		this.consents = consents;
		this.clock = clock;
	}

	/**
	 * 이미 가입한 회원인가.
	 *
	 * <p><b>가입과 나눈 이유</b>는 연령 게이트가 그 사이에 있기 때문이다 (B-13). 기존 회원이면
	 * 생년월일도 동의도 필요 없고, 최초 로그인이면 <b>계정을 만들기 전에</b> 판정해야 한다 —
	 * 만들고 나서 거부하면 나이를 확인받지 않은 계정이 남는다 (R10.2).
	 */
	@Transactional("identityTransactionManager")
	public Optional<UUID> findPlayerRef(OauthProvider provider, String subject) {
		return this.oauthIdentities.findByProviderAndSubject(provider, subject)
				.map(link -> playerRefOf(link.getUserId()));
	}

	/**
	 * 회원과 연결, 그리고 동의 이력을 함께 만든다 (§4.1).
	 *
	 * <p><b>한 트랜잭션이다.</b> 계정만 생기고 동의가 남지 않는 상태는 증빙이 없는 회원이고,
	 * 동의만 남고 계정이 없는 상태는 주인 없는 기록이다.
	 *
	 * <p>{@code (provider, subject)} 가 UNIQUE 이므로 동시에 두 요청이 들어와도 둘 다 만들지는
	 * 못한다 — 늦은 쪽은 제약 위반으로 실패한다. 그 상태를 조용히 흡수하지 않는다.
	 *
	 * @return 그 회원의 {@code playerRef}. <b>{@code user.id} 는 identity 밖으로 나가지 않는다</b> (I-3)
	 */
	@Transactional("identityTransactionManager")
	public UUID register(OauthProvider provider, VerifiedSocialIdentity verified, SignupInfo signup,
			String ipHash) {
		Instant now = this.clock.instant();
		User user = this.users.save(User.register(UUID.randomUUID(), signup.birthDate(), now));
		// 나이는 이미 판정을 통과했다. 그 사실을 시점과 함께 남긴다 (R10.2).
		user.markAgeVerified(now);
		this.oauthIdentities.save(
				OauthIdentity.link(user.getId(), provider, verified.subject(), verified.emailHash(), now));
		recordConsents(user.getId(), signup, ipHash, now);
		return user.getPlayerRef();
	}

	/**
	 * 동의를 남긴다 — 사용자가 체크한 것과 <b>서버가 판정한 것</b>.
	 *
	 * <p>{@link ConsentType#AGE} 는 화면의 체크박스가 아니다. 사용자가 "만 15세 이상입니다"에
	 * 체크했다는 사실보다 <b>서버가 생년월일로 확인했다는 사실</b>이 증빙이다 (R10.2).
	 * 판본은 판정 기준인 최소 연령을 그대로 쓴다.
	 */
	private void recordConsents(UUID userId, SignupInfo signup, String ipHash, Instant now) {
		for (SignupInfo.ConsentDecision decision : signup.consents()) {
			if (decision.agreed()) {
				this.consents.save(ConsentLog.agree(userId, decision.type(), decision.version(), ipHash, now));
			}
		}
		this.consents.save(ConsentLog.agree(userId, ConsentType.AGE,
				"age-" + AgeGate.MINIMUM_AGE, ipHash, now));
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

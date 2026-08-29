package com.neowadaeum.identity.purge;

import com.neowadaeum.common.spi.WithdrawnAccounts;
import com.neowadaeum.identity.domain.UserStatus;
import com.neowadaeum.identity.repository.AdminTotpRepository;
import com.neowadaeum.identity.repository.AiNoticeImpressionRepository;
import com.neowadaeum.identity.repository.OauthIdentityRepository;
import com.neowadaeum.identity.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 탈퇴 회원 파기 (R12.4, R12.5, B-61).
 *
 * <p><b>회원 행은 남고 고리가 끊긴다.</b> 동의 이력은 법정 기간 동안 보관해야 하고 (R12.4)
 * {@code consent_log} 가 회원 행을 앵커로 삼는다 — 행을 지우면 그 증빙이 함께 사라진다. 지워야
 * 하는 것은 회원 자체가 아니라 <b>회원과 기록을 잇는 값</b>이다 (R12.5).
 *
 * <p><b>매핑만 끊어서는 부족하다.</b> 소셜 계정 연결이 남아 있으면 같은 계정으로 다시 로그인했을
 * 때 지운 회원으로 돌아간다 — 그러면 파기한 것은 이름표뿐이다.
 *
 * <p><b>매핑을 마지막에 끊는다.</b> 앞 단계가 실패하면 그 회원은 다음 회차에 다시 대상이 된다.
 * 순서가 반대면 <b>지울 대상을 영영 찾지 못하는 데이터</b>가 남는다.
 */
@Service
public class WithdrawnAccountPurge implements WithdrawnAccounts {

	private final UserRepository users;

	private final OauthIdentityRepository socialAccounts;

	private final AiNoticeImpressionRepository noticeImpressions;

	private final AdminTotpRepository adminTotps;

	private final Clock clock;

	public WithdrawnAccountPurge(UserRepository users, OauthIdentityRepository socialAccounts,
			AiNoticeImpressionRepository noticeImpressions, AdminTotpRepository adminTotps,
			Clock clock) {
		this.users = users;
		this.socialAccounts = socialAccounts;
		this.noticeImpressions = noticeImpressions;
		this.adminTotps = adminTotps;
		this.clock = clock;
	}

	@Override
	@Transactional(value = "identityTransactionManager", readOnly = true)
	public List<UUID> pendingPurge() {
		return this.users.findPlayerRefsPendingPurge(UserStatus.WITHDRAWN);
	}

	/**
	 * <b>한 트랜잭션이다.</b> 소셜 연결만 지우고 매핑이 남거나 그 반대가 되면, 회원은
	 * <b>절반만 지워진 상태</b>로 남고 다음 회차가 그것을 어떻게 다뤄야 할지 알 수 없다.
	 */
	@Override
	@Transactional("identityTransactionManager")
	public int purge(Collection<UUID> playerRefs) {
		if (playerRefs.isEmpty()) {
			return 0;
		}
		List<UUID> userIds = this.users.findIdsPendingPurge(playerRefs, UserStatus.WITHDRAWN);
		if (userIds.isEmpty()) {
			return 0;
		}
		this.socialAccounts.deleteByUserIds(userIds);
		this.noticeImpressions.deleteByUserIds(userIds);
		this.adminTotps.deleteByUserIds(userIds);
		return this.users.purgePlayerRefs(playerRefs, UserStatus.WITHDRAWN,
				Instant.now(this.clock));
	}
}

package com.neowadaeum.identity.account;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.identity.domain.User;
import com.neowadaeum.identity.repository.UserRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 탈퇴 신청 (R12.5, B-62).
 *
 * <p><b>여기서는 상태만 옮긴다.</b> 실제 파기는 배치의 몫이며 (B-61), {@code UserStatus.WITHDRAWN}
 * 의 주석이 그 설계를 이미 적어 두었다 — <b>탈퇴 즉시 행을 지우면 진행 중 세션의 주인이 사라진다.</b>
 *
 * <p><b>왜 여기서 작품을 내리지 않는가</b> — identity 의 허용 의존은 {@code common} 하나다
 * (§5.4). 작품은 catalog 가 소유하고, 그것을 직접 부르면 스토어 경계가 무너진다. 공개 UGC 의
 * 강등은 배치가 매핑 파기보다 <b>먼저</b> 수행한다 (B-62 2/2, §13-9).
 *
 * <p><b>이미 탈퇴한 회원에게도 성공으로 답한다.</b> 탈퇴는 되돌릴 수 없으므로 두 번 눌러도
 * 결과가 같아야 하고, 여기서 오류를 주면 클라이언트는 <b>탈퇴에 실패했다</b>고 읽는다.
 */
@Service
public class AccountWithdrawalService {

	private final UserRepository users;

	public AccountWithdrawalService(UserRepository users) {
		this.users = users;
	}

	/**
	 * @param playerRef 요청 주체. identity 밖에서 들어오는 유일한 식별자다 (I-3)
	 * @throws ApiException {@code UNAUTHENTICATED} — 매핑이 이미 파기된 토큰이다
	 */
	@Transactional("identityTransactionManager")
	public void withdraw(UUID playerRef) {
		User user = this.users.findByPlayerRef(playerRef)
				// 파기된 회원의 토큰이 남아 있을 수 있다. 그 토큰에 답할 회원이 없다.
				.orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
		user.withdraw();
	}
}

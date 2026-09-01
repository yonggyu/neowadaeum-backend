package com.neowadaeum.identity.api;

import com.neowadaeum.common.web.PlayerRefResolver;
import com.neowadaeum.identity.account.AccountWithdrawalService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 계정 (R12.5, B-62).
 *
 * <p><b>{@code DELETE} 다.</b> 사용자가 요청하는 것은 상태 변경이 아니라 <b>계정을 없애는 것</b>이며,
 * 그 뒤에 남는 것(파기 배치가 처리할 상태)은 구현의 사정이지 계약의 내용이 아니다.
 *
 * <p><b>본문을 돌려주지 않는다</b> (204). 돌려줄 것이 남아 있다면 그것은 아직 탈퇴가 아니다.
 */
@RestController
@RequestMapping("/api/v1/me")
public class AccountController {

	private final AccountWithdrawalService withdrawal;

	private final PlayerRefResolver playerRefs;

	public AccountController(AccountWithdrawalService withdrawal, PlayerRefResolver playerRefs) {
		this.withdrawal = withdrawal;
		this.playerRefs = playerRefs;
	}

	/**
	 * 탈퇴 (§13.1, R12.5).
	 *
	 * <p><b>확인 절차를 서버에 두지 않는다.</b> "정말 탈퇴하시겠습니까"는 화면의 몫이고, 서버에
	 * 두면 그것은 <b>두 번 부르면 통과하는 관문</b>이 된다.
	 */
	@DeleteMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void withdraw() {
		this.withdrawal.withdraw(this.playerRefs.currentPlayerRef());
	}
}

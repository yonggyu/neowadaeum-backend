package com.neowadaeum.identity.api;

import com.neowadaeum.common.web.PlayerRefResolver;
import com.neowadaeum.identity.account.AccountWithdrawalService;
import com.neowadaeum.identity.account.MyAccountQueryService;
import com.neowadaeum.identity.account.MyAccountUpdateService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 계정 (R12.5, B-62, #262).
 *
 * <p><b>{@code DELETE} 다.</b> 사용자가 요청하는 것은 상태 변경이 아니라 <b>계정을 없애는 것</b>이며,
 * 그 뒤에 남는 것(파기 배치가 처리할 상태)은 구현의 사정이지 계약의 내용이 아니다.
 *
 * <p><b>본문을 돌려주지 않는다</b> (204). 돌려줄 것이 남아 있다면 그것은 아직 탈퇴가 아니다.
 *
 * <p><b>{@code GET} 이 세션 복원의 유일한 물음이다</b> (#262). 토큰이 없으면 보안 체인이 401 을
 * 내고, 있으면 200 이다 — <b>그 두 코드가 곧 답</b>이며 본문에 로그인 여부를 담지 않는다.
 */
@RestController
@RequestMapping("/api/v1/me")
public class AccountController {

	private final AccountWithdrawalService withdrawal;

	private final MyAccountQueryService myAccount;

	private final MyAccountUpdateService update;

	private final PlayerRefResolver playerRefs;

	public AccountController(AccountWithdrawalService withdrawal, MyAccountQueryService myAccount,
			MyAccountUpdateService update, PlayerRefResolver playerRefs) {
		this.withdrawal = withdrawal;
		this.myAccount = myAccount;
		this.update = update;
		this.playerRefs = playerRefs;
	}

	/**
	 * 내 계정 (#262).
	 *
	 * <p><b>파라미터가 없다.</b> 누구인지는 토큰이 정하며, 조회 대상을 요청이 고를 수 있게 하면
	 * 그 순간 <b>남의 계정을 읽는 경로</b>가 된다.
	 */
	@GetMapping
	public MeResponse me() {
		return MeResponse.of(this.myAccount.myAccount(this.playerRefs.currentPlayerRef()));
	}

	/**
	 * 표시명 설정·변경 (#271, §13-7).
	 *
	 * <p><b>{@code PATCH} 다.</b> 계정의 일부만 바꾸며, {@code PUT} 으로 두면 보내지 않은 필드를
	 * 지운다는 뜻이 되어 <b>이름만 바꾸려는 요청이 나머지를 날린다.</b>
	 *
	 * <p><b>설정과 변경이 같은 요청이다.</b> 프로필이 없으면 만들고 있으면 바꾼다 — 화면이 어느
	 * 쪽인지 먼저 물어볼 필요가 없다.
	 *
	 * <p><b>응답이 {@code MeResponse} 다.</b> 저장된 값은 정규화 결과라 보낸 값과 다를 수 있고
	 * (양끝 공백 · 연속 공백 · NFC), 돌려주지 않으면 화면은 <b>다시 {@code GET} 을 불러야</b> 자기가
	 * 무엇을 저장했는지 안다.
	 */
	@PatchMapping
	public MeResponse updateMe(@Valid @RequestBody UpdateMeRequest request) {
		return MeResponse.of(
				this.update.updateDisplayName(this.playerRefs.currentPlayerRef(), request.displayName()));
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

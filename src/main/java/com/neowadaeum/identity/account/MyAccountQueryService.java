package com.neowadaeum.identity.account;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.common.spi.AuthorDisplayNameQuery;
import com.neowadaeum.identity.domain.User;
import com.neowadaeum.identity.repository.UserRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 내 계정 조회 (#262).
 *
 * <p><b>"로그인돼 있는가"에 답하는 것이 이 조회의 첫 용도다.</b> 프론트는 토큰을 메모리에만
 * 두므로(XSS 로 새지 않게) 새로고침하면 무엇을 들고 있는지 알 수 없다 — 물어볼 곳이 필요하다.
 * 답은 <b>본문이 아니라 상태 코드</b>다: 토큰이 유효하면 200, 아니면 보안 체인이 401 을 낸다.
 * 응답에 {@code isLoggedIn} 같은 필드를 만들지 않는다 — 랜딩(§13.10)이 그것을 만들지 않은 이유와
 * 같다.
 *
 * <p><b>두 스토어를 한 트랜잭션으로 묶지 않는다.</b> 회원은 identity 에, 표시명은 catalog 에
 * 있다. 각 조회는 자기 스토어의 읽기 전용 트랜잭션에서 끝나며 (Repository 와 SPI 구현이 각각
 * 갖고 있다), 그 둘을 감싸는 트랜잭션을 여기에 두면 <b>스토어 경계를 넘는 트랜잭션</b>이 생긴다.
 *
 * <p><b>표시명이 없어도 실패하지 않는다.</b> 프로필은 UGC 를 쓸 때 생기는 것이고, 읽기만 하는
 * 회원에게는 없는 것이 정상이다.
 */
@Service
public class MyAccountQueryService {

	private final UserRepository users;

	private final AuthorDisplayNameQuery displayNames;

	public MyAccountQueryService(UserRepository users, AuthorDisplayNameQuery displayNames) {
		this.users = users;
		this.displayNames = displayNames;
	}

	/**
	 * <p><b>탈퇴한 회원은 그 사실을 본다.</b> 탈퇴는 상태이고(R12.5), 파기 배치가 돌기 전까지 이미
	 * 발급된 액세스 토큰은 만료 전까지 살아 있다 — 그 사이에 이 경로가 평범한 {@code active} 를
	 * 돌려주면 화면은 <b>아무 일도 없었던 것처럼</b> 보인다. 상태를 그대로 알린다.
	 *
	 * @param playerRef 요청 주체. identity 밖에서 들어오는 유일한 식별자다 (I-3)
	 * @throws ApiException {@code UNAUTHENTICATED} — 매핑이 이미 파기된 토큰이다 (R12.5)
	 */
	public MyAccount myAccount(UUID playerRef) {
		User user = this.users.findByPlayerRef(playerRef)
				// 파기된 회원의 토큰이 남아 있을 수 있다. 그 토큰에 답할 회원이 없다.
				.orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));

		return new MyAccount(this.displayNames.findDisplayName(playerRef).orElse(null), user.getRole(),
				user.getStatus());
	}
}

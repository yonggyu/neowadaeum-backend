package com.neowadaeum.identity.account;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.common.spi.AuthorDisplayNameWriter;
import com.neowadaeum.common.spi.InvalidDisplayNameException;
import com.neowadaeum.identity.domain.User;
import com.neowadaeum.identity.domain.UserStatus;
import com.neowadaeum.identity.repository.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 내 계정 변경 (#271).
 *
 * <p><b>표시명을 쓰는 유일한 프로덕션 경로다.</b> 읽는 곳은 셋이었는데(작품 상세 · 커뮤니티 카드 ·
 * {@code GET /me}) 만드는 곳은 테스트뿐이었고, 그래서 실사용에서 값이 항상 {@code null} 이었다.
 *
 * <p><b>두 스토어를 한 트랜잭션으로 묶지 않는다.</b> 회원은 identity 에, 표시명은 catalog 에
 * 있으며 DataSource 가 다르다 — 감싸는 트랜잭션을 두면 스토어 경계를 넘는 트랜잭션이 된다
 * ({@link MyAccountQueryService} 와 같은 판단이다). 여기서 identity 는 <b>읽기만</b> 하고 쓰기는
 * catalog 한쪽에서만 일어나므로 <b>갈라져서 반쪽만 남을 상태가 없다.</b> 실패하면 이름이 바뀌지
 * 않았을 뿐이고, 다시 부르면 된다.
 *
 * <p><b>정지·탈퇴 회원은 이름을 바꾸지 못한다.</b> 액세스 토큰은 탈퇴 후에도 만료 전까지 살아
 * 있으므로(R12.5) 여기서 막지 않으면, 파기 배치가 {@code "탈퇴한 사용자"} 로 바꿔 둔 이름을
 * <b>탈퇴한 계정이 도로 되돌릴 수 있다.</b>
 */
@Service
public class MyAccountUpdateService {

	private final UserRepository users;

	private final AuthorDisplayNameWriter displayNames;

	public MyAccountUpdateService(UserRepository users, AuthorDisplayNameWriter displayNames) {
		this.users = users;
		this.displayNames = displayNames;
	}

	/**
	 * 표시명을 설정하거나 바꾼다.
	 *
	 * <p><b>바뀐 계정을 그대로 돌려준다.</b> 저장된 값은 정규화 결과라 입력과 다를 수 있고
	 * (양끝 공백 · 연속 공백 · NFC), 돌려주지 않으면 화면은 <b>자기가 보낸 값을 저장된 값이라고
	 * 믿는다.</b> catalog 를 다시 읽지 않는 것은 쓰기가 이미 그 값을 알기 때문이다.
	 *
	 * @param playerRef 요청 주체. identity 밖으로 나가는 유일한 식별자다 (I-3)
	 * @throws ApiException {@code UNAUTHENTICATED} 매핑이 파기됐다 · {@code FORBIDDEN} 정지·탈퇴 ·
	 * {@code VALIDATION_ERROR} 표시명이 규칙에 맞지 않는다 (#287)
	 */
	public MyAccount updateDisplayName(UUID playerRef, String displayName) {
		User user = this.users.findByPlayerRef(playerRef)
				.orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
		if (user.getStatus() != UserStatus.ACTIVE) {
			throw new ApiException(ErrorCode.FORBIDDEN);
		}

		String stored = store(playerRef, displayName);
		return new MyAccount(stored, user.getRole(), user.getStatus());
	}

	/**
	 * <b>규칙 위반을 400 으로 바꾼다.</b> 판정은 catalog 도메인이 하고(정본이 하나여야 한다)
	 * HTTP 로의 사상은 요청을 받은 쪽이 한다.
	 *
	 * <p><b>거절된 값을 응답에 담지 않는다</b> (S-3) — 어긴 규칙만 말한다. 모양은
	 * {@code @Valid} 실패와 같다: {@code details.fields[].field} · {@code .reason}.
	 */
	private String store(UUID playerRef, String displayName) {
		try {
			return this.displayNames.updateDisplayName(playerRef, displayName);
		}
		catch (InvalidDisplayNameException ex) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR,
					Map.<String, Object>of("fields",
							List.of(Map.of("field", "displayName", "reason", ex.reason()))),
					ex);
		}
	}
}

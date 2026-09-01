package com.neowadaeum.common.spi;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 탈퇴 회원의 파기 (R12.4, R12.5, B-61, ADR-0003).
 *
 * <p><b>두 단계로 나뉜 것은 순서 때문이다.</b> 회원을 가리키는 값은 {@code playerRef} 하나뿐이고
 * (§2.1, I-3) 그 매핑은 {@code identity} 에만 있다 — <b>매핑을 먼저 지우면 다른 스토어는 무엇을
 * 지워야 할지 알 수 없게 된다.</b> 그래서 대상을 먼저 묻고, 다른 모듈이 자기 데이터를 지운 뒤,
 * 마지막에 매핑을 끊는다.
 *
 * <p><b>중간에 멈춰도 안전하다.</b> 매핑이 마지막이므로 앞 단계가 실패하면 그 회원은 다음 회차에
 * 다시 대상이 된다. 반대 순서였다면 <b>지울 대상을 영영 찾지 못하는 데이터</b>가 남는다.
 *
 * <p><b>회원 행 자체는 지우지 않는다.</b> 동의 이력은 법정 기간 동안 남아야 하고 (R12.4) 그것이
 * 회원 행을 앵커로 삼는다. 지우는 것은 회원이 아니라 <b>회원과 기록을 잇는 고리</b>다 (R12.5).
 *
 * @see PlayerDataPurge
 */
public interface WithdrawnAccounts {

	/**
	 * 탈퇴했고 아직 파기되지 않은 회원의 {@code playerRef} 들.
	 *
	 * <p><b>회원 식별자가 아니라 {@code playerRef} 를 준다</b> (I-3). 이 값을 받는 쪽은
	 * identity 밖이며, {@code user.id} 는 그 경계를 넘지 않는다.
	 */
	List<UUID> pendingPurge();

	/**
	 * 매핑을 파기한다 (R12.5).
	 *
	 * @param playerRefs {@link #pendingPurge()} 가 준 값들
	 * @return 파기된 회원 수. batch 가 구조화 로그에 남긴다 (§9.4)
	 */
	int purge(Collection<UUID> playerRefs);
}

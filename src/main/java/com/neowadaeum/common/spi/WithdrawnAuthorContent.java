package com.neowadaeum.common.spi;

import java.util.Collection;
import java.util.UUID;

/**
 * 탈퇴한 작성자의 작품 처리 (R12.5, §13-9, B-62, ADR-0003).
 *
 * <p>R12.5 는 매핑 파기를 요구하면서 <b>단서를 달았다</b> — "공개된 UGC 작품은 예외 처리가
 * 필요하다". 매핑만 끊으면 그 작품은 <b>주인을 알 수 없는 채로 계속 공개된 상태</b>가 되고,
 * 작성자는 자기 작품을 내릴 수단을 잃는다.
 *
 * <p><b>지우지 않고 내린다</b> (§13-9 기본 채택안). 작품에는 그것을 플레이한 사람들의 기록이
 * 매달려 있고 (세션 · 도달률), 지우면 <b>남의 기록까지 함께 사라진다.</b> 그래서 공개를 멈추고
 * 작성자명을 익명으로 바꾼다.
 *
 * <p><b>매핑 파기보다 먼저 불린다.</b> 작품을 찾는 값이 {@code author_ref}({@code playerRef}) 이며,
 * 매핑이 먼저 사라지면 <b>어느 작품이 그 사람의 것인지 알 수 없다.</b>
 *
 * @see WithdrawnAccounts
 */
public interface WithdrawnAuthorContent {

	/**
	 * 그 작성자들의 작품을 내리고 이름을 지운다.
	 *
	 * @param authorRefs {@link WithdrawnAccounts#pendingPurge()} 가 준 값들
	 * @return 공개가 멈춘 작품 수. batch 가 구조화 로그에 남긴다 (§9.4)
	 */
	int handleWithdrawal(Collection<UUID> authorRefs);
}

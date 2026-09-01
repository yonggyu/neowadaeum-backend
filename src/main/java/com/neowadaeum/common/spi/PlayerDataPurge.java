package com.neowadaeum.common.spi;

import java.util.Collection;
import java.util.UUID;

/**
 * 탈퇴 회원의 플레이 기록 파기 (R12.4, B-61, ADR-0003).
 *
 * <p><b>만료와 다르다.</b> 무활동 만료는 <b>이어갈 수만 없게</b> 만들고 기록을 남긴다
 * ({@link SessionExpiry}) — 사용자가 자기가 어디까지 갔었는지를 잃지 않기 위해서다. 탈퇴에는
 * 그 사용자가 없다. <b>남겨 둘 이유가 사라진 기록을 남기는 것은 보관이 아니라 방치다.</b>
 *
 * <p><b>구현은 데이터를 소유한 모듈이 한다</b> (ADR-0003). 무엇이 세션에 매달려 있는지는
 * 그쪽만 알고, batch 가 그것을 알면 스토어 경계가 사라진다.
 *
 * @see WithdrawnAccounts
 */
public interface PlayerDataPurge {

	/**
	 * 그 회원들의 기록을 지운다.
	 *
	 * <p><b>매핑이 아직 살아 있을 때 불린다</b> — 이 순서가 뒤집히면 지울 대상을 찾을 수 없다
	 * ({@link WithdrawnAccounts} 의 설명).
	 *
	 * @return 지워진 세션 수. batch 가 구조화 로그에 남긴다 (§9.4)
	 */
	int purge(Collection<UUID> playerRefs);
}

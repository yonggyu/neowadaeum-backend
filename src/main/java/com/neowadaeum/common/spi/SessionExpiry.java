package com.neowadaeum.common.spi;

/**
 * 오래 손대지 않은 세션을 만료로 바꾼다 (§4.7, B-61, ADR-0003).
 *
 * <p><b>지우는 것이 아니다.</b> 기록은 남고 <b>이어갈 수만 없게</b> 된다 — Resume 이
 * {@code expired} 로 답하고(R13.3), 지나간 플레이는 계속 읽힌다. 지우는 것은 탈퇴가 부르는
 * 일이며 (R12.4) 그것은 B-61(2/2)이다.
 *
 * <p><b>구현은 {@code play} 다</b> — 세션을 소유한 모듈이며 ADR-0003 이 <b>"실행 결과 적재는
 * 구현 모듈이 한다"</b> 고 정했다.
 *
 * @see LogRetentionPurge
 */
public interface SessionExpiry {

	/**
	 * 무활동 기간이 지난 세션을 한 차례 만료시킨다.
	 *
	 * @return 만료된 세션 수. batch 가 구조화 로그에 남긴다 (§9.4)
	 */
	int expireIdleSessions();
}

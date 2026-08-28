package com.neowadaeum.play.api;

import com.neowadaeum.common.spi.SessionExpiry;
import com.neowadaeum.common.support.RetentionProperties;
import com.neowadaeum.play.domain.SessionStatus;
import com.neowadaeum.play.repository.PlaySessionRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 무활동 세션 만료 (§4.7, R13.3, B-61).
 *
 * <p><b>지우는 것이 아니라 상태를 바꾼다.</b> 기록은 남고 이어갈 수만 없게 되며, Resume 이
 * {@code expired} 로 답한다 — 사용자가 <b>자기가 어디까지 갔었는지</b>를 잃지 않는다.
 * 지우는 것은 탈퇴가 부르는 일이며 (R12.4) 그것은 B-61(2/2)이다.
 *
 * <p><b>지운 세션은 건드리지 않는다.</b> 사용자가 이미 지운 것을 배치가 만료로 바꾸면, 지운
 * 이유와 만료된 이유가 한 행에서 섞인다.
 *
 * <p><b>{@code active} 만 본다.</b> {@code completed} 는 끝난 이야기이고 {@code abandoned} 는
 * 사용자가 버린 것이다 — 둘 다 <b>이어갈 수 없다는 사실이 이미 성립</b>하므로 만료로 덮어쓰면
 * 왜 그렇게 됐는지만 잃는다.
 */
@Service
public class IdleSessionExpiry implements SessionExpiry {

	private final PlaySessionRepository sessions;

	private final RetentionProperties retention;

	private final Clock clock;

	public IdleSessionExpiry(PlaySessionRepository sessions, RetentionProperties retention,
			Clock clock) {
		this.sessions = sessions;
		this.retention = retention;
		this.clock = clock;
	}

	@Override
	@Transactional("playTransactionManager")
	public int expireIdleSessions() {
		Instant now = Instant.now(this.clock);
		return this.sessions.expireIdle(SessionStatus.EXPIRED, SessionStatus.ACTIVE,
				now.minus(this.retention.sessionIdleLimit()), now);
	}
}

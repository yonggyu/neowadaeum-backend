package com.neowadaeum.batch;

import com.neowadaeum.common.spi.LogRetentionPurge;
import com.neowadaeum.common.spi.PlayerDataPurge;
import com.neowadaeum.common.spi.SessionExpiry;
import com.neowadaeum.common.spi.WithdrawnAccounts;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 보관 기간 배치 (R12.4, S-10, §4.7, B-61).
 *
 * <p><b>약관이 이미 그렇게 적혀 있다.</b> 지운다고 적어 두고 지우지 않으면 그것은 미구현이
 * 아니라 <b>거짓 고지</b>다 — S-10 이 "파기 배치를 <b>실제로 구현하고 테스트한다</b>" 를
 * 명시한 이유다.
 *
 * <p><b>셋을 한 회차에 부른다.</b> 로그 파기 · 세션 만료 · 탈퇴 파기는 스토어도 소유 모듈도
 * 다르지만 <b>같은 질문에 답한다</b> — 지난 것을 언제까지 들고 있는가. 주기를 나누면 "지금 보관
 * 상태가 어떤가"를 세 로그에서 맞춰 봐야 한다.
 *
 * <p><b>하나가 실패해도 나머지는 돈다.</b> 스토어가 다르므로 함께 묶을 트랜잭션도 없고,
 * 한 스토어의 장애가 다른 스토어의 파기를 미룰 이유도 없다.
 *
 * <p><b>이 클래스는 언제 부르는지만 안다</b> (ADR-0003). 무엇을 얼마나 지우는지는 구현과
 * 설정이 정한다.
 */
@Component
public class RetentionBatch {

	/** 잠금 이름. batch 소유다 (ADR-0003). */
	static final String LOCK_KEY = "batch:retention";

	/** 한 회차가 이 시간 안에 끝난다고 본다. 죽은 인스턴스의 잠금이 스스로 풀려야 한다. */
	private static final Duration LOCK_TTL = Duration.ofMinutes(30);

	private static final Logger log = LoggerFactory.getLogger(RetentionBatch.class);

	private final LogRetentionPurge logs;

	private final SessionExpiry sessions;

	private final WithdrawnAccounts accounts;

	private final PlayerDataPurge playerData;

	private final StringRedisTemplate redis;

	public RetentionBatch(LogRetentionPurge logs, SessionExpiry sessions, WithdrawnAccounts accounts,
			PlayerDataPurge playerData, StringRedisTemplate redis) {
		this.logs = logs;
		this.sessions = sessions;
		this.accounts = accounts;
		this.playerData = playerData;
		this.redis = redis;
	}

	/**
	 * 하루 한 번 (R12.4).
	 *
	 * <p>보관 기간은 일 단위이므로 그보다 자주 돌 이유가 없다 — 자주 돌면 지울 것이 없는 회차만
	 * 늘어난다.
	 */
	@Scheduled(fixedDelayString = "${app.batch.retention.delay:P1D}", initialDelayString = "PT15M")
	public void run() {
		if (!Boolean.TRUE.equals(this.redis.opsForValue().setIfAbsent(LOCK_KEY, "1", LOCK_TTL))) {
			// 다른 인스턴스가 돌고 있다. 기다리지 않는다 — 다음 회차가 있다.
			return;
		}
		long startedAt = System.nanoTime();
		try {
			int purgedLogs = purgeLogs();
			int expiredSessions = expireSessions();
			int purgedAccounts = purgeWithdrawnAccounts();
			log.info("batch.retention.done purgedLogs={} expiredSessions={} purgedAccounts={} "
					+ "tookMs={}", purgedLogs, expiredSessions, purgedAccounts,
					(System.nanoTime() - startedAt) / 1_000_000);
		}
		finally {
			this.redis.delete(LOCK_KEY);
		}
	}

	/**
	 * <b>실패를 삼키되 수치는 남긴다.</b>
	 *
	 * <p>던지면 스케줄러가 멈추고, 그러면 <b>이후 모든 회차의 파기가 사라진다</b> — 보관 기간을
	 * 어기는 가장 조용한 방법이다. 파기는 누적이 아니라 조건이므로 한 회차를 걸러도 다음 회차가
	 * 같은 것을 지운다.
	 */
	private int purgeLogs() {
		try {
			return this.logs.purgeExpiredLogs();
		}
		catch (RuntimeException ex) {
			log.error("batch.retention.logs.failed reason={}", ex.getClass().getSimpleName(), ex);
			return 0;
		}
	}

	private int expireSessions() {
		try {
			return this.sessions.expireIdleSessions();
		}
		catch (RuntimeException ex) {
			log.error("batch.retention.sessions.failed reason={}", ex.getClass().getSimpleName(), ex);
			return 0;
		}
	}

	/**
	 * 탈퇴 회원 파기 (R12.4, R12.5).
	 *
	 * <p><b>플레이 기록이 먼저다.</b> 회원을 가리키는 값은 {@code playerRef} 하나뿐이고 그 매핑은
	 * identity 에만 있다 — <b>매핑을 먼저 끊으면 다른 스토어는 무엇을 지워야 할지 알 수 없게
	 * 된다.</b> 이 순서 덕분에 중간에 실패해도 안전하다: 그 회원은 다음 회차에 다시 대상이 된다.
	 *
	 * <p><b>스토어가 둘이라 함께 묶을 트랜잭션이 없다.</b> 그래서 순서가 곧 안전장치다.
	 */
	private int purgeWithdrawnAccounts() {
		try {
			List<UUID> pending = this.accounts.pendingPurge();
			if (pending.isEmpty()) {
				return 0;
			}
			this.playerData.purge(pending);
			return this.accounts.purge(pending);
		}
		catch (RuntimeException ex) {
			log.error("batch.retention.accounts.failed reason={}", ex.getClass().getSimpleName(), ex);
			return 0;
		}
	}
}

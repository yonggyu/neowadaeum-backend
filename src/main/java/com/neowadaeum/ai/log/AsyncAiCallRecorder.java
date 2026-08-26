package com.neowadaeum.ai.log;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * 호출 기록을 <b>비동기로</b> 남긴다 (B-25, R4.6 과 같은 성질).
 *
 * <p><b>사용자 대기 시간에 들어가지 않는다.</b> 턴 응답은 이미 25초 예산 안에서 아슬아슬하고
 * (§13-19), 거기에 DB 쓰기를 더하면 <b>관측을 붙인 만큼 지연이 늘어난다.</b>
 *
 * <p><b>기록 실패를 삼킨다.</b> 예외를 밖으로 내면 로그가 본문을 막는다 — 관측은 서비스보다
 * 나중이다. 대신 <b>실패했다는 사실은 남긴다</b>: 원문 없이, 세션 좌표까지만 (S-3).
 *
 * <p><b>이 클래스에는 원문을 찍는 로그가 없다.</b> S-3 이 금지하는 것이 정확히 그것이며,
 * 원문 보관처는 {@code ai_call_log} 하나다.
 */
public class AsyncAiCallRecorder implements AiCallRecorder {

	private static final Logger log = LoggerFactory.getLogger(AsyncAiCallRecorder.class);

	private final AiCallLogRepository repository;

	private final ExecutorService executor;

	private final Clock clock;

	public AsyncAiCallRecorder(AiCallLogRepository repository, ExecutorService executor, Clock clock) {
		this.repository = repository;
		this.executor = executor;
		this.clock = clock;
	}

	@Override
	public void record(AiCallLog.Draft draft) {
		Instant now = Instant.now(this.clock);
		this.executor.execute(() -> save(draft, now));
	}

	/**
	 * <b>시각은 제출 시점에 찍는다.</b> 저장 시점에 찍으면 실행기가 밀렸을 때 순서가 뒤집히고,
	 * 그 순서로 재요청을 읽으면 <b>2회차가 1회차보다 먼저 일어난 것처럼 보인다.</b>
	 */
	@Transactional("promptLogTransactionManager")
	protected void save(AiCallLog.Draft draft, Instant now) {
		try {
			this.repository.save(AiCallLog.record(draft, now));
		}
		catch (RuntimeException ex) {
			// 좌표까지만 남긴다. 원문도 예외 본문도 남기지 않는다 (S-3).
			log.warn("failed to record an ai call: purpose={} attempt={}", draft.purpose(), draft.attemptNo());
		}
	}
}

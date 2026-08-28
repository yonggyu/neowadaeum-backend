package com.neowadaeum.batch;

import com.neowadaeum.common.spi.UgcRescan;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 승인작 재스캔 배치 (R9.4, B-59).
 *
 * <p><b>이 클래스는 언제 부르는지만 안다.</b> 무엇을 어떻게 보는지는 {@code authoring} 의
 * 구현이 정한다 — ADR-0003 이 <b>"실행 결과 적재는 구현 모듈이 한다"</b> 고 못박았고, batch 가
 * 적재하면 {@code batch → authoring} 의존이 생겨 {@code admin → batch} 와 만나 순환이 된다.
 *
 * <p><b>갱신을 감지하지 않고 주기로 돈다.</b> R9.4 는 <b>재스캔하는 배치</b>를 요구하며, 갱신
 * 이벤트에 매다는 안은 <b>한 번 놓치면 그 갱신이 영원히 반영되지 않는다</b> — 주기 실행은 놓친
 * 회차를 다음 회차가 덮는다.
 *
 * <p><b>실패해도 다음 회차가 있다.</b> 재스캔은 누적이 아니라 <b>다시 보는 일</b>이므로 한 번
 * 걸러도 결과가 어긋나지 않는다 — 그래서 예외를 삼키고 로그로 남긴다. 던지면 스케줄러가 멈춘다.
 */
@Component
public class UgcRescanBatch {

	/** 잠금 이름. batch 소유다 (ADR-0003). */
	static final String LOCK_KEY = "batch:ugc-rescan";

	/** 한 회차가 이 시간 안에 끝난다고 본다. 죽은 인스턴스의 잠금이 스스로 풀려야 한다. */
	private static final Duration LOCK_TTL = Duration.ofMinutes(30);

	private static final Logger log = LoggerFactory.getLogger(UgcRescanBatch.class);

	private final UgcRescan rescan;

	private final StringRedisTemplate redis;

	public UgcRescanBatch(UgcRescan rescan, StringRedisTemplate redis) {
		this.rescan = rescan;
		this.redis = redis;
	}

	/**
	 * 주기 실행 (R9.4).
	 *
	 * <p>{@code fixedDelay} 다 — 앞 회차가 끝난 뒤부터 센다. {@code fixedRate} 로 두면 재스캔이
	 * 느려질 때 회차가 겹쳐 쌓인다.
	 */
	@Scheduled(fixedDelayString = "${app.batch.ugc-rescan.delay:PT6H}", initialDelayString = "PT5M")
	public void run() {
		if (!Boolean.TRUE.equals(this.redis.opsForValue().setIfAbsent(LOCK_KEY, "1", LOCK_TTL))) {
			// 다른 인스턴스가 돌고 있다. 기다리지 않는다 — 다음 회차가 있다.
			return;
		}
		long startedAt = System.nanoTime();
		try {
			int suspended = this.rescan.rescan();
			// **작품 id 를 남기지 않는다** (S-11). 몇 건인지는 운영 지표이고, 어느 작품이
			// 걸렸는지는 검수 큐가 답한다.
			log.info("batch.ugc-rescan.done suspended={} tookMs={}", suspended,
					(System.nanoTime() - startedAt) / 1_000_000);
		}
		catch (RuntimeException ex) {
			// 던지면 스케줄러가 멈춘다. 재스캔은 다시 보는 일이므로 한 회차를 걸러도 어긋나지 않는다.
			log.error("batch.ugc-rescan.failed reason={}", ex.getClass().getSimpleName(), ex);
		}
		finally {
			this.redis.delete(LOCK_KEY);
		}
	}
}

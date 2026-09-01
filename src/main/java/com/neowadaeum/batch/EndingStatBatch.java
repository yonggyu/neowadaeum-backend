package com.neowadaeum.batch;

import com.neowadaeum.common.spi.EndingStatAggregation;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 엔딩 도달률 집계 배치 (R2.7, I-20, B-39).
 *
 * <p><b>이 클래스는 언제 부르는지만 안다.</b> 어디서 읽어 어디에 쓰는지는 {@code catalog} 의
 * 구현이 정한다 — ADR-0003 이 <b>"실행 결과 적재는 구현 모듈이 한다"</b> 고 못박았고, batch 가
 * 적재하면 {@code batch → catalog} 의존이 생겨 {@code admin → batch} 와 만나 순환이 된다.
 *
 * <p><b>인스턴스가 둘이어도 한 번만 돈다.</b> 잠금 이름은 batch 소유다(ADR-0003) —
 * "언제 어떻게 실행하는가"의 일부다. ShedLock 대신 <b>이미 있는 Redis</b> 로 단일 실행을
 * 보장한다: 의존을 하나 더 들이는 것보다 가진 것으로 같은 성질을 얻는 편이 낫다.
 *
 * <p><b>실패해도 다음 회차가 있다.</b> 집계는 누적이 아니라 <b>다시 계산</b>이므로 한 번 걸러도
 * 값이 어긋나지 않는다 — 그래서 예외를 삼키고 로그로 남긴다. 던지면 스케줄러가 멈춘다.
 */
@Component
public class EndingStatBatch {

	/** 잠금 이름. batch 소유다 (ADR-0003). */
	static final String LOCK_KEY = "batch:ending-stat";

	/** 한 회차가 이 시간 안에 끝난다고 본다. 죽은 인스턴스의 잠금이 스스로 풀려야 한다. */
	private static final Duration LOCK_TTL = Duration.ofMinutes(10);

	private static final Logger log = LoggerFactory.getLogger(EndingStatBatch.class);

	private final EndingStatAggregation aggregation;

	private final StringRedisTemplate redis;

	public EndingStatBatch(EndingStatAggregation aggregation, StringRedisTemplate redis) {
		this.aggregation = aggregation;
		this.redis = redis;
	}

	/**
	 * 주기 실행 (R2.7).
	 *
	 * <p>{@code fixedDelay} 다 — 앞 회차가 끝난 뒤부터 센다. {@code fixedRate} 로 두면 집계가
	 * 느려질 때 회차가 겹쳐 쌓인다.
	 */
	@Scheduled(fixedDelayString = "${app.batch.ending-stat.delay:PT1H}", initialDelayString = "PT1M")
	public void run() {
		if (!Boolean.TRUE.equals(this.redis.opsForValue().setIfAbsent(LOCK_KEY, "1", LOCK_TTL))) {
			// 다른 인스턴스가 돌고 있다. 기다리지 않는다 — 다음 회차가 있다.
			return;
		}
		long startedAt = System.nanoTime();
		try {
			int rows = this.aggregation.refresh();
			log.info("batch.ending-stat.done rows={} tookMs={}", rows,
					(System.nanoTime() - startedAt) / 1_000_000);
		}
		catch (RuntimeException ex) {
			// 던지면 스케줄러가 멈춘다. 집계는 다시 계산이므로 한 회차를 걸러도 값이 어긋나지 않는다.
			log.error("batch.ending-stat.failed reason={}", ex.getClass().getSimpleName(), ex);
		}
		finally {
			this.redis.delete(LOCK_KEY);
		}
	}
}

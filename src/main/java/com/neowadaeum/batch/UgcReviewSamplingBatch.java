package com.neowadaeum.batch;

import com.neowadaeum.common.spi.UgcReviewSampling;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 승인 후 샘플링 검수 배치 (R8.11, B-59).
 *
 * <p><b>이 클래스는 언제 부르는지만 안다.</b> 비율도, 무엇을 뽑는지도, 큐에 어떻게 올리는지도
 * {@code authoring} 의 구현이 정한다 (ADR-0003).
 *
 * <p><b>재스캔과 주기가 다르다.</b> 재스캔은 <b>블록리스트가 바뀌었을 수 있다</b>에 반응하고
 * 샘플링은 <b>검수 인력이 하루에 볼 수 있는 양</b>에 맞춘다 — 하나로 묶으면 한쪽 주기를 바꿀
 * 때 다른 쪽이 함께 흔들린다.
 *
 * <p><b>실패해도 다음 회차가 있다.</b> 샘플링은 누적이 아니라 <b>매번 다시 뽑는 일</b>이므로
 * 한 회차를 걸러도 결과가 어긋나지 않는다 — 그래서 예외를 삼키고 로그로 남긴다. 던지면
 * 스케줄러가 멈춘다.
 */
@Component
public class UgcReviewSamplingBatch {

	/** 잠금 이름. batch 소유다 (ADR-0003). */
	static final String LOCK_KEY = "batch:ugc-review-sampling";

	/** 한 회차가 이 시간 안에 끝난다고 본다. 죽은 인스턴스의 잠금이 스스로 풀려야 한다. */
	private static final Duration LOCK_TTL = Duration.ofMinutes(30);

	private static final Logger log = LoggerFactory.getLogger(UgcReviewSamplingBatch.class);

	private final UgcReviewSampling sampling;

	private final StringRedisTemplate redis;

	public UgcReviewSamplingBatch(UgcReviewSampling sampling, StringRedisTemplate redis) {
		this.sampling = sampling;
		this.redis = redis;
	}

	/**
	 * 주기 실행 (R8.11).
	 *
	 * <p>{@code fixedDelay} 다 — 앞 회차가 끝난 뒤부터 센다. {@code fixedRate} 로 두면 회차가
	 * 겹쳐 <b>같은 날 두 번 뽑는</b> 일이 생긴다.
	 */
	@Scheduled(fixedDelayString = "${app.batch.ugc-review-sampling.delay:P1D}",
			initialDelayString = "PT10M")
	public void run() {
		if (!Boolean.TRUE.equals(this.redis.opsForValue().setIfAbsent(LOCK_KEY, "1", LOCK_TTL))) {
			// 다른 인스턴스가 돌고 있다. 기다리지 않는다 — 다음 회차가 있다.
			return;
		}
		long startedAt = System.nanoTime();
		try {
			int flagged = this.sampling.sample();
			// **비율도 작품 id 도 남기지 않는다** (S-11). 몇 건인지는 운영 지표이고, 어느 작품이
			// 뽑혔는지는 검수 큐가 답한다.
			log.info("batch.ugc-review-sampling.done flagged={} tookMs={}", flagged,
					(System.nanoTime() - startedAt) / 1_000_000);
		}
		catch (RuntimeException ex) {
			// 던지면 스케줄러가 멈춘다. 샘플링은 매번 다시 뽑는 일이므로 한 회차를 걸러도 된다.
			log.error("batch.ugc-review-sampling.failed reason={}", ex.getClass().getSimpleName(), ex);
		}
		finally {
			this.redis.delete(LOCK_KEY);
		}
	}
}

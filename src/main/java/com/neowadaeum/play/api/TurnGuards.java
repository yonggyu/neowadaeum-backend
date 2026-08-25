package com.neowadaeum.play.api;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 턴 요청의 동시성·연속 실패 가드 (§4.3-2, R6.5).
 *
 * <p>두 가지를 본다.
 *
 * <ul>
 *   <li><b>동시 생성 락</b> — 계정당 1개 (§4.3-2). 실패하면 {@code 409 CONCURRENT_GENERATION}
 *   <li><b>연속 실패 카운터</b> — 3회면 {@code 429 RETRY_COOLDOWN} + {@code retryAfterSeconds: 30}
 *       (R6.5). <b>클라이언트 쿨다운과 별개로 서버가 강제한다</b>
 * </ul>
 *
 * <p>Redis 를 쓰는 이유는 프로세스 간 공유다. 인스턴스가 둘이면 인메모리 카운터는 계정당 두 배를
 * 허용한다.
 *
 * <p><b>락은 반드시 풀린다.</b> TTL 을 서버 응답 예산(§6.3)보다 조금 길게 두어, 프로세스가 죽어
 * {@code finally} 가 돌지 못해도 <b>한 계정이 영구히 막히지 않게</b> 한다.
 */
@Component
public class TurnGuards {

	/** R6.5 — 연속 실패 3회. */
	static final int FAILURE_LIMIT = 3;

	/** R6.5 — 서버가 강제하는 대기 시간. */
	static final Duration COOLDOWN = Duration.ofSeconds(30);

	/** §6.3 의 서버 전체 예산 28초보다 길다. 죽은 프로세스가 남긴 락이 스스로 풀려야 한다. */
	private static final Duration LOCK_TTL = Duration.ofSeconds(35);

	private static final Duration FAILURE_WINDOW = Duration.ofMinutes(10);

	private final StringRedisTemplate redis;

	public TurnGuards(StringRedisTemplate redis) {
		this.redis = redis;
	}

	/**
	 * 동시 생성 락을 잡는다 (§4.3-2).
	 *
	 * @throws ApiException 이미 생성 중이면 {@code 409 CONCURRENT_GENERATION}
	 */
	public void acquireGenerationLock(UUID playerRef) {
		boolean acquired = Boolean.TRUE.equals(
				this.redis.opsForValue().setIfAbsent(lockKey(playerRef), "1", LOCK_TTL));
		if (!acquired) {
			throw new ApiException(ErrorCode.CONCURRENT_GENERATION);
		}
	}

	/** 락을 푼다. <b>{@code finally} 에서 부른다</b> — 실패 경로에서 빠뜨리면 계정이 막힌다. */
	public void releaseGenerationLock(UUID playerRef) {
		this.redis.delete(lockKey(playerRef));
	}

	/**
	 * 쿨다운에 걸렸는지 본다 (R6.5).
	 *
	 * @throws ApiException 연속 실패가 한도에 닿았으면 {@code 429 RETRY_COOLDOWN}
	 */
	public void requireNotCoolingDown(UUID sessionId) {
		String failures = this.redis.opsForValue().get(failureKey(sessionId));
		if (failures != null && Integer.parseInt(failures) >= FAILURE_LIMIT) {
			throw new ApiException(ErrorCode.RETRY_COOLDOWN,
					Map.of("retryAfterSeconds", COOLDOWN.toSeconds()));
		}
	}

	/**
	 * 실패를 센다.
	 *
	 * <p>창을 두는 이유는 <b>어제의 실패가 오늘을 막지 않게</b> 하기 위해서다. 한도에 닿으면
	 * 쿨다운 길이로 만료를 다시 잡아, 그 시간이 지나면 저절로 풀린다.
	 */
	public void recordFailure(UUID sessionId) {
		Long failures = this.redis.opsForValue().increment(failureKey(sessionId));
		if (failures != null && failures == 1L) {
			this.redis.expire(failureKey(sessionId), FAILURE_WINDOW);
		}
		if (failures != null && failures >= FAILURE_LIMIT) {
			this.redis.expire(failureKey(sessionId), COOLDOWN);
		}
	}

	/** 성공하면 연속이 끊긴다. "연속" 실패이므로 누적이 아니다 (R6.5). */
	public void recordSuccess(UUID sessionId) {
		this.redis.delete(failureKey(sessionId));
	}

	/** 계정 기준이다 (§4.3-2 — "동시 생성 락(계정당 1개)"). 세션 기준이면 여러 작품을 동시에 돌린다. */
	private static String lockKey(UUID playerRef) {
		return "play:generating:" + playerRef;
	}

	private static String failureKey(UUID sessionId) {
		return "play:failures:" + sessionId;
	}
}

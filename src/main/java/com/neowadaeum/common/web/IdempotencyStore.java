package com.neowadaeum.common.web;

import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@code Idempotency-Key} 저장소 (R6.2, §5.2 {@code common/web}).
 *
 * <p><b>보호 대상은 중복 과금이다.</b> 와이어프레임의 "다시 시도"는 <b>같은 {@code choiceId} 를
 * 재전송</b>하므로(R6.3), 그대로 두면 Provider 가 두 번 불린다.
 *
 * <p>세 상태로 움직인다.
 *
 * <ol>
 *   <li>{@link #reserve} — 처음 온 요청이 자리를 잡는다. 이미 있으면 실패한다
 *   <li>{@link #complete} — 결과를 채운다
 *   <li>{@link #awaitResult} — 진행 중인 요청의 결과를 <b>기다렸다</b> 받는다
 * </ol>
 *
 * <p><b>기다리는 것이 요점이다.</b> 진행 중이라고 곧바로 409 를 주면 클라이언트가 다시 눌러
 * 결국 두 번 생성된다 — R6.2 는 "기존 결과를 대기해 반환한다"를 요구한다.
 *
 * <p>Redis 를 쓰는 이유는 <b>프로세스 간</b> 공유가 필요하기 때문이다. 인스턴스가 둘이면
 * 인메모리 맵은 아무것도 막지 못한다.
 */
public class IdempotencyStore {

	/** 진행 중임을 나타내는 자리표. 결과가 채워지면 이 값이 사라진다. */
	private static final String IN_PROGRESS = "";

	/** 턴 하나의 수명보다 넉넉하되 무한하지 않게. 서버 예산 28초(§6.3)의 몇 배다. */
	private static final Duration RETENTION = Duration.ofMinutes(5);

	private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

	private final StringRedisTemplate redis;

	public IdempotencyStore(StringRedisTemplate redis) {
		this.redis = redis;
	}

	/**
	 * 자리를 잡는다.
	 *
	 * @return 이 요청이 처음이면 {@code true}. 이미 같은 키가 있으면 {@code false}
	 */
	public boolean reserve(String key) {
		return Boolean.TRUE.equals(this.redis.opsForValue().setIfAbsent(key, IN_PROGRESS, RETENTION));
	}

	/** 결과를 채운다. 대기 중인 요청들이 이 값을 받아 간다. */
	public void complete(String key, String result) {
		this.redis.opsForValue().set(key, result, RETENTION);
	}

	/**
	 * 실패한 요청의 자리를 비운다.
	 *
	 * <p>비우지 않으면 <b>같은 키로는 영영 재시도할 수 없다.</b> 실패는 결과가 아니므로 남길
	 * 이유도 없다.
	 */
	public void release(String key) {
		this.redis.delete(key);
	}

	/**
	 * 진행 중인 요청의 결과를 기다린다.
	 *
	 * @param budget 기다릴 시간. 서버 전체 응답 예산(§6.3) 안이어야 한다
	 * @return 결과. 시간 안에 채워지지 않으면 비어 있다 — 호출자가 판단한다
	 */
	public Optional<String> awaitResult(String key, Duration budget) {
		long deadline = System.nanoTime() + budget.toNanos();

		while (System.nanoTime() < deadline) {
			String value = this.redis.opsForValue().get(key);
			if (value == null) {
				// 앞선 요청이 실패해 자리를 비웠다. 기다려도 오지 않는다.
				return Optional.empty();
			}
			if (!IN_PROGRESS.equals(value)) {
				return Optional.of(value);
			}
			try {
				Thread.sleep(POLL_INTERVAL.toMillis());
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return Optional.empty();
			}
		}
		return Optional.empty();
	}
}

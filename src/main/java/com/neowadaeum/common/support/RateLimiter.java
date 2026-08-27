package com.neowadaeum.common.support;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 고정 창 호출 한도 (§15, S-8, B-38).
 *
 * <p><b>Redis 를 쓰는 이유는 프로세스 간 공유다.</b> 인스턴스가 둘이면 인메모리 카운터는 한도를
 * 두 배로 만든다 — {@code TurnGuards} 가 같은 이유로 Redis 를 쓴다.
 *
 * <p><b>고정 창이다.</b> 창 경계에서 최대 두 배가 통과할 수 있다(분당 10이면 59초와 61초에 각각
 * 10). 슬라이딩 창이 정확하지만 값 하나가 아니라 목록을 들고 있어야 하고, 여기서 막으려는 것은
 * <b>정밀한 초당 분포가 아니라 폭주</b>다. 정밀도가 필요해지는 시점은 B-46 의 실측 이후다.
 *
 * <p><b>키에 창 번호를 넣는다.</b> TTL 만으로 창을 관리하면 첫 요청 시각에 따라 창이 흐르고,
 * 그러면 같은 사용자의 창과 다른 사용자의 창이 어긋나 한도가 사실상 달라진다.
 */
@Component
public class RateLimiter {

	private final StringRedisTemplate redis;

	public RateLimiter(StringRedisTemplate redis) {
		this.redis = redis;
	}

	/**
	 * 한 번 센다.
	 *
	 * @param scope  한도의 종류. 키 접두어가 되며 <b>계정 기준과 IP 기준이 섞이지 않게</b> 한다
	 * @param key    계정({@code playerRef}) 또는 IP 해시. <b>원문 IP 를 넘기지 않는다</b> (§12)
	 * @param limit  창당 허용 횟수
	 * @param window 창 길이
	 * @return 허용되면 {@code true}. 넘겼으면 {@code false}
	 */
	public boolean tryAcquire(String scope, String key, int limit, Duration window) {
		String windowKey = "rate:%s:%s:%d".formatted(scope, key,
				System.currentTimeMillis() / window.toMillis());
		Long count = this.redis.opsForValue().increment(windowKey);
		if (count != null && count == 1L) {
			// 첫 증가에서만 만료를 건다. 매번 걸면 창이 계속 밀려 사실상 만료되지 않는다.
			this.redis.expire(windowKey, window);
		}
		return count != null && count <= limit;
	}

	/**
	 * 지금 창이 끝날 때까지 남은 초.
	 *
	 * <p><b>언제 다시 시도할지 알려주지 않으면 클라이언트가 즉시 재시도한다</b> — 그러면 한도는
	 * 트래픽을 줄이는 것이 아니라 실패를 늘리는 장치가 된다.
	 */
	public long retryAfterSeconds(Duration window) {
		long elapsed = System.currentTimeMillis() % window.toMillis();
		return Math.max(1, (window.toMillis() - elapsed) / 1000);
	}
}

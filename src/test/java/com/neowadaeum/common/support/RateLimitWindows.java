package com.neowadaeum.common.support;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 테스트가 호출 한도 창을 <b>한 번에</b> 소진시킨다 (이슈 #217).
 *
 * <p><b>왜 반복 호출로는 안 되는가.</b> {@link RateLimiter} 는 키에 창 번호를 넣는 고정 창이다
 * (§13-28). 한도만큼 {@code tryAcquire} 를 돌리는 동안 창 경계를 넘으면 <b>키가 바뀌어 카운터가
 * 1부터 다시 센다</b> — 그러면 막혀야 할 다음 요청이 통과하고, 테스트는 한도 게이트가 아니라
 * 그 뒤의 코드를 만나 <b>엉뚱한 실패</b>로 끝난다. 테스트 설정의 분당 한도가 1000, 일일 한도가
 * 5000 이므로 느린 러너에서는 그 구간이 실제로 분 경계를 넘었다.
 *
 * <p>한 번의 증가로 바꾸면 소진과 그다음 요청 사이의 구간이 밀리초 단위로 줄어든다.
 *
 * <p><b>키 문자열을 여기서 만들지 않는다.</b> 창 번호가 키에 들어간다는 것은 {@code RateLimiter}
 * 의 지식이므로 {@link RateLimiter#windowKey} 를 그대로 쓴다 — 같은 패키지에 있는 이유가
 * 이것이다. 복제하면 형식이 바뀌는 날 <b>어긋난 채로 조용히 통과한다.</b>
 */
public final class RateLimitWindows {

	private RateLimitWindows() {
	}

	/**
	 * 그 창을 한도까지 채운다. 이 호출 뒤의 첫 {@code tryAcquire} 는 막힌다.
	 *
	 * <p>만료를 함께 건다 — {@code tryAcquire} 는 <b>첫 증가에서만</b> 만료를 걸므로, 여기서
	 * 걸지 않으면 그 키가 남아 <b>다음 테스트까지 소진 상태를 물려준다.</b>
	 */
	public static void exhaust(StringRedisTemplate redis, RateLimiter limiter, String scope,
			String key, int limit, Duration window) {
		String windowKey = limiter.windowKey(scope, key, window);
		redis.opsForValue().increment(windowKey, limit);
		redis.expire(windowKey, window);
	}
}

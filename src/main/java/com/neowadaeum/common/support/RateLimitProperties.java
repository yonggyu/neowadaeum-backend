package com.neowadaeum.common.support;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 호출 한도 (§15, S-8, B-38).
 *
 * <p>§15 가 값을 정했다 — <b>턴 생성 분당 10회 · precheck 분당 20회 · 계정당 일일 한도.</b>
 * 설정으로 두는 이유는 B-46 이 실측한 뒤 조정할 값이기 때문이다.
 *
 * <p><b>{@code common} 이 소유한다</b> (§5.4). 지금 읽는 곳은 {@code play} 지만 precheck 는
 * {@code authoring}(B-50)이고 인증 경로는 {@code identity} 다 — 셋이 같은 표를 봐야 한다.
 *
 * @param turnPerMinute      §15 — 턴 생성 분당 10회
 * @param precheckPerMinute  §15 — precheck 분당 20회 (B-50 이 쓴다)
 * @param authPerMinutePerIp S-8 — 계정 없이 부를 수 있는 경로의 IP 기준 제한
 * @param turnPerDay         §15 의 "계정당 일일 토큰 한도"를 <b>턴 수로 대리한다</b> (§13-28)
 */
@ConfigurationProperties("app.rate-limit")
public record RateLimitProperties(Integer turnPerMinute, Integer precheckPerMinute,
		Integer authPerMinutePerIp, Integer turnPerDay) {

	/** 창 길이는 값이 아니라 규칙이다 — §15 가 "분당"이라고 적었다. */
	public static final Duration MINUTE = Duration.ofMinutes(1);

	/** 하루 창. 자정 경계가 아니라 24시간 고정 창이다 — 시간대 논쟁을 만들지 않는다. */
	public static final Duration DAY = Duration.ofDays(1);

	public RateLimitProperties {
		turnPerMinute = (turnPerMinute != null) ? turnPerMinute : 10;
		precheckPerMinute = (precheckPerMinute != null) ? precheckPerMinute : 20;
		authPerMinutePerIp = (authPerMinutePerIp != null) ? authPerMinutePerIp : 20;
		turnPerDay = (turnPerDay != null) ? turnPerDay : 200;
	}

	/** 설정을 띄우지 않는 테스트가 쓴다. */
	public static RateLimitProperties defaults() {
		return new RateLimitProperties(null, null, null, null);
	}
}

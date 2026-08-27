package com.neowadaeum.common.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * B-38 — <b>§15 의 값이 코드에 그대로 있는가.</b>
 *
 * <p>통합 테스트는 한도를 올려 돌린다(40턴 E2E 가 정당하게 그만큼 부른다). 그래서 <b>값 자체는
 * 여기서 못박는다</b> — 그러지 않으면 설정 기본값이 조용히 달라져도 아무도 모른다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class RateLimitPropertiesTests {

	/** §15 — 턴 생성 분당 10회 · precheck 분당 20회. */
	@Test
	void S15_defaults_match_the_documented_limits() {
		RateLimitProperties limits = RateLimitProperties.defaults();

		assertThat(limits.turnPerMinute()).isEqualTo(10);
		assertThat(limits.precheckPerMinute()).isEqualTo(20);
	}

	/** S-8 — 인증 전 경로는 IP 로 센다. 계정 기준으로 셀 수 없기 때문이다. */
	@Test
	void S8_the_ip_limit_exists_and_is_positive() {
		assertThat(RateLimitProperties.defaults().authPerMinutePerIp()).isPositive();
	}

	/** §13-28 — 일일 한도는 턴 수로 대리한다. 값이 있고 분당 한도보다 크다. */
	@Test
	void S13_28_the_daily_limit_is_larger_than_the_per_minute_one() {
		RateLimitProperties limits = RateLimitProperties.defaults();

		assertThat(limits.turnPerDay()).isGreaterThan(limits.turnPerMinute());
	}

	/** 설정이 값을 덮는다 — B-46 실측 후 조정할 수 있어야 한다. */
	@Test
	void S15_configuration_overrides_the_defaults() {
		assertThat(new RateLimitProperties(3, 4, 5, 6).turnPerMinute()).isEqualTo(3);
	}

	/** 창 길이는 값이 아니라 규칙이다 — §15 가 "분당"이라고 적었다. */
	@Test
	void S15_the_window_is_a_minute_and_a_day() {
		assertThat(RateLimitProperties.MINUTE).isEqualTo(Duration.ofMinutes(1));
		assertThat(RateLimitProperties.DAY).isEqualTo(Duration.ofDays(1));
	}
}

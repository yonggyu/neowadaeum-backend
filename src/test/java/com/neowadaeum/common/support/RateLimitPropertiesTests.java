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
	void SEC8_the_ip_limit_exists_and_is_positive() {
		assertThat(RateLimitProperties.defaults().authPerMinutePerIp()).isPositive();
	}

	/**
	 * S-8 — 인증 없이 열리는 설정 조회에도 한도가 있다 (이슈 #277).
	 *
	 * <p>{@code /landing} 과 {@code /consents} 가 <b>이 값 하나</b>를 함께 쓴다. 값이 사라지거나
	 * 0 이 되면 <b>토큰 없이 부를 수 있는 DB 읽기가 다시 무제한</b>이 된다.
	 */
	@Test
	void S13_10_the_public_read_limit_exists_and_is_positive() {
		assertThat(RateLimitProperties.defaults().publicReadPerMinutePerIp()).isPositive();
	}

	/**
	 * <b>탐색의 한도는 설정 조회와 <i>다른 값</i>이다</b> (§13-54, 이슈 #306).
	 *
	 * <p>같은 값을 쓰면 창을 나눈 의미가 절반만 남는다 — 탐색은 한 화면이 섹션 더 보기와 작품
	 * 상세로 이어져 설정 조회보다 훨씬 잦다. 값이 0 이 되면 <b>둘러보기가 통째로 막힌다.</b>
	 */
	@Test
	void S13_54_the_browse_limit_is_positive_and_looser_than_the_config_read_one() {
		RateLimitProperties limits = RateLimitProperties.defaults();

		assertThat(limits.publicBrowsePerMinutePerIp()).isPositive()
				.isGreaterThan(limits.publicReadPerMinutePerIp());
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
		assertThat(new RateLimitProperties(3, 4, 5, 6, 7, 8, 9).turnPerMinute()).isEqualTo(3);
	}

	/** 창 길이는 값이 아니라 규칙이다 — §15 가 "분당"이라고 적었다. */
	@Test
	void S15_the_window_is_a_minute_and_a_day() {
		assertThat(RateLimitProperties.MINUTE).isEqualTo(Duration.ofMinutes(1));
		assertThat(RateLimitProperties.DAY).isEqualTo(Duration.ofDays(1));
	}
}

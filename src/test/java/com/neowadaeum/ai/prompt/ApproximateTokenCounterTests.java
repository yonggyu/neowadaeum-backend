package com.neowadaeum.ai.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * B-20 — 토큰 근사 (`[결정 필요]`).
 *
 * <p>여기서 고정하는 것은 "정확한 토큰 수"가 아니다. 그런 값은 벤더마다 다르고 이 계산기는 벤더를
 * 모른다. 고정하는 것은 <b>틀리는 방향</b>이다 — 적게 세어 예산을 넘기는 일이 없어야 한다.
 */
class ApproximateTokenCounterTests {

	private final TokenCounter counter = new ApproximateTokenCounter();

	@Test
	void B20_nothing_costs_nothing() {
		assertThat(this.counter.count(null)).isZero();
		assertThat(this.counter.count("")).isZero();
	}

	/** ASCII 는 4자에 1토큰으로 본다. */
	@Test
	void B20_ascii_is_counted_at_four_characters_per_token() {
		assertThat(this.counter.count("abcd")).isEqualTo(1);
		assertThat(this.counter.count("abcde")).isEqualTo(2);
	}

	/**
	 * <b>한글은 글자마다 1토큰으로 센다 — 실제보다 많게.</b>
	 *
	 * <p>과소 추정은 예산을 넘긴 요청을 보내 Provider 오류와 비용을 낳고, 과대 추정은 컨텍스트를 조금
	 * 덜 싣는다. 되돌릴 수 없는 쪽은 전자다.
	 */
	@Test
	void B20_korean_is_over_estimated_on_purpose() {
		assertThat(this.counter.count("안녕하세요")).isEqualTo(5);
		assertThat(this.counter.count("안녕 hello")).isEqualTo(2 + 2);
	}
}

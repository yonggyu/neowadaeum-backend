package com.neowadaeum.common.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * B-20 / #82 — 토큰 근사.
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

	/**
	 * <b>한국어를 글자 수보다 적게 세지 않는다.</b>
	 *
	 * <p>이 프로젝트의 본문은 한국어다. 여기서 과소 추정하면 예산을 넘긴 요청이 실제로 나가고,
	 * 그 비용과 오류는 되돌릴 수 없다. 계수를 어떻게 조정하든 이 성질은 유지돼야 한다.
	 */
	@Test
	void B20_korean_is_never_counted_below_its_character_count() {
		String korean = "눈이 오래 내리는 도시입니다";
		long hangul = korean.codePoints().filter(codePoint -> codePoint > 0x7F).count();

		assertThat(this.counter.count(korean)).isGreaterThanOrEqualTo((int) hangul);
		assertThat(this.counter.count("안녕하세요")).isGreaterThanOrEqualTo(5);
	}

	/** 계수와 안전 여유가 실제로 곱해진다 — 한글 5자 × 1.3 × 1.1 = 7.15 → 8. */
	@Test
	void B20_the_declared_coefficients_are_the_ones_applied() {
		assertThat(ApproximateTokenCounter.CJK_TOKENS_PER_CHAR).isEqualTo(1.3);
		assertThat(ApproximateTokenCounter.SAFETY_MARGIN).isEqualTo(1.1);
		assertThat(this.counter.count("안녕하세요")).isEqualTo(8);
	}

	/** ASCII 는 4자에 1토큰. 같은 글자 수라도 한국어보다 훨씬 싸다. */
	@Test
	void B20_ascii_is_cheaper_than_korean_for_the_same_length() {
		assertThat(this.counter.count("abcdefgh")).isLessThan(this.counter.count("가나다라마바사아"));
	}

	/** 이모지·기호는 가장 비싸게 센다. 여러 조각으로 쪼개져 나오는 부류다. */
	@Test
	void B20_emoji_are_counted_as_the_most_expensive_class() {
		assertThat(this.counter.count("🌨")).isGreaterThan(this.counter.count("눈"));
	}
}

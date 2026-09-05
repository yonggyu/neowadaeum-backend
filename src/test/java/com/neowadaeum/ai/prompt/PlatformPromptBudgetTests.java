package com.neowadaeum.ai.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.ai.prompt.PromptLayer.BudgetGroup;
import com.neowadaeum.common.support.ApproximateTokenCounter;
import com.neowadaeum.common.support.TokenCounter;
import org.junit.jupiter.api.Test;

/**
 * §4.3 · R4.9 — <b>플랫폼 문구의 길이가 예산으로 정해져 있다</b> (B-20).
 *
 * <p>여기서만 <b>운영 계산기</b>를 쓴다. 골든 테스트는 고정 계산기를 쓰지만(#82), 이 테스트가 보는
 * 것은 프롬프트의 모양이 아니라 <b>실제로 예산에 들어가는가</b> 이므로 운영에서 쓰는 계산이어야
 * 의미가 있다. 계수가 바뀌면 이 테스트가 함께 움직이는 것이 맞다.
 *
 * <p>이 제약이 없으면 문구가 조용히 길어지고, <b>예산 초과는 첫 실사용 턴에서</b> 드러난다.
 */
class PlatformPromptBudgetTests {

	private final TokenCounter counter = new ApproximateTokenCounter();

	/**
	 * <b>{@code SYSTEM} 에 남는 몫은 200 토큰이다.</b>
	 *
	 * <p>{@code FOUNDATION} 은 1,200 이고 R4.9 가 UGC 의 {@code world_prompt} + {@code persona_prompt}
	 * 합계를 1,000 으로 하드 제한한다. 뺄셈이 그대로 상한이 된다 — 넘기면 <b>정상 작품이 예산을
	 * 넘긴다.</b>
	 */
	@Test
	void R4_9_the_system_layer_fits_in_what_the_ugc_cap_leaves() {
		int ugcHardLimit = 1_000;
		int room = BudgetGroup.FOUNDATION.maxTokens() - ugcHardLimit;

		assertThat(this.counter.count(PlatformPrompts.SYSTEM)).isLessThanOrEqualTo(room);
	}

	/**
	 * <b>{@code OUTPUT SPEC} 은 {@code USER ACTION} 과 200 을 나눈다.</b>
	 *
	 * <p>사용자가 고른 선택지 본문이 들어갈 자리를 남겨야 한다. 넉넉히 잡아 40자짜리 선택지를
	 * 기준으로 둔다 — 실제 선택지는 그보다 짧다 (R5.3 의 문장 길이 지시).
	 */
	@Test
	void S4_3_the_output_spec_leaves_room_for_the_user_action() {
		String longChoice = "가".repeat(40);
		int used = this.counter.count(PlatformPrompts.OUTPUT_SPEC) + this.counter.count(longChoice);

		assertThat(used).isLessThanOrEqualTo(BudgetGroup.INSTRUCTION.maxTokens());
	}

	/**
	 * <b>{@code STATE VOCABULARY} 의 머리글이 이름의 자리를 다 먹지 않는다</b> (§13-76).
	 *
	 * <p>이 묶음 200 은 머리글과 <b>작품이 선언한 이름</b>이 나눠 쓴다. 머리글이 길어지면 이름이
	 * 먼저 밀려나고, 밀려난 이름은 모델이 맞힐 수 없다 (#367). 절반 이상을 이름 몫으로 남긴다.
	 */
	@Test
	void S13_76_the_vocabulary_header_leaves_most_of_the_group_to_the_names() {
		int header = this.counter.count(PlatformPrompts.STATE_VOCABULARY_HEADER);

		assertThat(header).isLessThan(BudgetGroup.STATE_VOCABULARY.maxTokens() / 2);
	}

	/**
	 * 형식 설명을 한국어 산문이 아니라 JSON 골격으로 쓴 이유가 값으로 드러난다.
	 *
	 * <p>ASCII 는 한글의 1/5 값이다. 같은 내용을 문장으로 풀면 예산에 들어가지 않는다.
	 */
	@Test
	void B20_the_output_spec_is_cheaper_than_the_same_text_in_korean_prose() {
		assertThat(this.counter.count(PlatformPrompts.OUTPUT_SPEC))
				.isLessThan(this.counter.count("가".repeat(PlatformPrompts.OUTPUT_SPEC.length())));
	}
}

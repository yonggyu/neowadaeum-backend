package com.neowadaeum.ai.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.ai.prompt.PromptLayer.BudgetGroup;
import com.neowadaeum.common.support.ApproximateTokenCounter;
import com.neowadaeum.common.support.TokenCounter;
import com.neowadaeum.play.port.StateChangeOperator;
import java.util.Arrays;
import java.util.List;
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
	 * <b>{@code OUTPUT SPEC} 은 {@code USER ACTION} 과 {@code INSTRUCTION} 묶음을 나눈다.</b>
	 *
	 * <p>사용자가 고른 선택지 본문이 들어갈 자리를 남겨야 한다. 넉넉히 잡아 40자짜리 선택지를
	 * 기준으로 둔다 — 실제 선택지는 그보다 짧다 (R5.3 의 문장 길이 지시).
	 *
	 * <p><b>연산자 표기가 이 문구로 오면서 묶음이 225 로 늘었다</b> (§13-82). 늘어난 25 는
	 * {@code STATE VOCABULARY} 에서 왔고 총 예산 4,000 은 그대로다 — 그 합계는
	 * {@code PromptAssemblerTests} 가 센다. <b>여기서 재는 것은 이 문구가 자기 묶음에 들어가는가</b>다.
	 */
	@Test
	void S4_3_the_output_spec_leaves_room_for_the_user_action() {
		String longChoice = "가".repeat(40);
		int used = this.counter.count(PlatformPrompts.OUTPUT_SPEC) + this.counter.count(longChoice);

		assertThat(used).isLessThanOrEqualTo(BudgetGroup.INSTRUCTION.maxTokens());
	}

	/**
	 * <b>{@code OUTPUT SPEC} 이 허용 연산자를 하나도 빠뜨리지 않는다</b> (§13-82, R4.1).
	 *
	 * <p>이 자리가 연산자 표기의 <b>정본</b>이다. 목록에서 빠진 연산자는 모델이 표기를 맞혀야 하고,
	 * 어긋난 표기는 예외 없이 조용히 버려진다 — 증상은 <b>"상태가 가끔 안 바뀐다"</b> 하나뿐이다.
	 */
	@Test
	void R4_1_the_output_spec_prints_every_allowed_operator() {
		assertThat(PlatformPrompts.OUTPUT_SPEC)
				.contains(Arrays.stream(StateChangeOperator.values())
						.map(operator -> "\"%s\": %s".formatted(operator.key(), operator.wireShape()))
						.toList())
				.contains("\"<%s>\": %s"
						.formatted(StateChangeOperator.NUMERIC, StateChangeOperator.NUMERIC_WIRE_SHAPE));
	}

	/**
	 * <b>어휘 레이어는 연산자 표기를 말하지 않는다</b> (§13-82).
	 *
	 * <p>같은 사실을 두 자리가 말하면 한쪽만 고치는 날 모델이 <b>모순된 지시</b>를 받는다 (#375).
	 * 이 레이어가 답하는 물음은 <b>"어떤 이름을 쓸 수 있는가"</b> 하나로 좁혀졌다.
	 */
	@Test
	void S13_82_the_vocabulary_layer_names_no_operator() {
		String block = PlatformPrompts.stateVocabulary(new PromptContext.StateVocabulary(
				List.of("affinity.yuna"), List.of("met_yuna"), List.of("letter")));

		assertThat(block).doesNotContain(Arrays.stream(StateChangeOperator.values())
				.map(StateChangeOperator::key)
				.toList());
	}

	/**
	 * <b>어휘 레이어의 머리표는 연산자 키에서 온다</b> (§13-82).
	 *
	 * <p>표기를 빼도 <b>어느 갈래인지</b>는 남아야 한다. 그 머리표가 연산자 키와 다른 낱말을 쓰면
	 * 모델은 {@code flags} 목록과 {@code flags.add} 를 잇지 못하고, 그때 이름을 알려 준 효과가
	 * 사라진다.
	 */
	@Test
	void S13_82_the_vocabulary_labels_are_the_operator_namespaces() {
		String block = PlatformPrompts.stateVocabulary(new PromptContext.StateVocabulary(
				List.of("affinity.yuna"), List.of("met_yuna"), List.of("letter")));

		assertThat(block)
				.contains("\n%s = affinity.yuna".formatted(StateChangeOperator.NUMERIC))
				.contains("\n%s = met_yuna".formatted(StateChangeOperator.FLAGS_ADD.namespace()))
				.contains("\n%s = letter".formatted(StateChangeOperator.INVENTORY_ADD.namespace()));
		assertThat(StateChangeOperator.FLAGS_ADD.key())
				.startsWith(StateChangeOperator.FLAGS_ADD.namespace() + ".");
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

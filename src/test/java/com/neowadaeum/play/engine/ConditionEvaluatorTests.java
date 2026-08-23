package com.neowadaeum.play.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-6 (#57) — 판정이 <b>결정론적이고 예측 가능한가</b>를 확인한다.
 *
 * <p>여기서 지키는 것은 "조건이 잘 계산되는가"만이 아니다. <b>틀린 조건식이 턴을 죽이지 않고,
 * 조용히 통과하지도 않는가</b>가 함께 대상이다 — 작품 데이터는 사람이 만들고 오타가 난다.
 *
 * <p>컨테이너가 필요 없다. 평가기는 순수 함수이고 빠른 루프에서 돌아야 한다 (ADR-0001).
 */
class ConditionEvaluatorTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final ConditionEvaluator evaluator = new ConditionEvaluator();

	private static GameState state() {
		return GameState.from(JSON.readTree("""
				{"chapter": 2, "turn": 7,
				 "location": "교실", "timeOfDay": "오후",
				 "affinity": {"yuna": 30},
				 "flags": ["first_talk"],
				 "inventory": ["letter"]}"""));
	}

	private boolean eval(String condition) {
		return this.evaluator.evaluate(JSON.readTree(condition), state());
	}

	/** R7.4 — 문서에 적힌 예시가 그대로 평가된다. 다른 것이 다 맞아도 이게 틀리면 소용없다. */
	@Test
	void R7_4_documented_example_evaluates() {
		assertThat(eval("""
				{"all": [{"gte": ["affinity.yuna", 30]}, {"has": ["flags", "first_talk"]}]}""")).isTrue();

		assertThat(eval("""
				{"all": [{"gte": ["affinity.yuna", 31]}, {"has": ["flags", "first_talk"]}]}""")).isFalse();
	}

	// ── 비교 연산자 ─────────────────────────────────────────

	/** B-27 — `gte` `gt` `lte` `lt` `eq` 는 경계값에서 갈린다. */
	@Test
	void B27_numeric_comparisons_are_exact_at_the_boundary() {
		assertThat(eval("""
				{"gte": ["affinity.yuna", 30]}""")).isTrue();
		assertThat(eval("""
				{"gt": ["affinity.yuna", 30]}""")).isFalse();
		assertThat(eval("""
				{"lte": ["affinity.yuna", 30]}""")).isTrue();
		assertThat(eval("""
				{"lt": ["affinity.yuna", 30]}""")).isFalse();
		assertThat(eval("""
				{"eq": ["affinity.yuna", 30]}""")).isTrue();
	}

	/**
	 * 없는 수치를 {@code 0} 으로 보지 않는다.
	 *
	 * <p>{@code 0} 으로 보면 {@code min} 이 양수인 스키마에서 "아직 값이 없다"와 "0 이다"가 같은 뜻이
	 * 되고, {@code lt} 계열이 조용히 참이 된다 — 도달하면 안 되는 엔딩이 열린다.
	 */
	@Test
	void B27_absent_numeric_is_false_for_every_comparison_including_lt() {
		assertThat(eval("""
				{"lt": ["affinity.nobody", 10]}""")).isFalse();
		assertThat(eval("""
				{"gte": ["affinity.nobody", 0]}""")).isFalse();
	}

	// ── has ─────────────────────────────────────────────────

	/** B-27 — `has` 는 `flags` 와 `inventory` 두 컬렉션을 본다. */
	@Test
	void B27_has_checks_flags_and_inventory() {
		assertThat(eval("""
				{"has": ["flags", "first_talk"]}""")).isTrue();
		assertThat(eval("""
				{"has": ["flags", "snow_left"]}""")).isFalse();
		assertThat(eval("""
				{"has": ["inventory", "letter"]}""")).isTrue();
		assertThat(eval("""
				{"has": ["inventory", "key"]}""")).isFalse();
	}

	/** 알 수 없는 컬렉션은 false 다. 추측해서 flags 로 읽지 않는다. */
	@Test
	void B27_has_on_an_unknown_collection_is_false() {
		assertThat(eval("""
				{"has": ["achievements", "first_talk"]}""")).isFalse();
	}

	// ── turnGte ─────────────────────────────────────────────

	/** B-27 — `turnGte` 는 정수 하나를 받는다. 이름에 대상과 연산이 이미 들어 있다. */
	@Test
	void B27_turn_gte_compares_the_current_turn() {
		assertThat(eval("""
				{"turnGte": 7}""")).isTrue();
		assertThat(eval("""
				{"turnGte": 8}""")).isFalse();
	}

	// ── 논리 연산자 ─────────────────────────────────────────

	/** B-27 — `any` 는 하나라도 참이면 참이다. */
	@Test
	void B27_any_is_true_when_one_branch_holds() {
		assertThat(eval("""
				{"any": [{"gte": ["affinity.yuna", 99]}, {"has": ["flags", "first_talk"]}]}""")).isTrue();
	}

	/** B-27 — `not` 은 조건 객체 하나를 받는다. */
	@Test
	void B27_not_negates_a_single_condition() {
		assertThat(eval("""
				{"not": {"has": ["flags", "snow_left"]}}""")).isTrue();
		assertThat(eval("""
				{"not": {"has": ["flags", "first_talk"]}}""")).isFalse();
	}

	/** 중첩이 임의 깊이로 동작한다. 작성 도구가 조합을 만들면 깊어진다 (R7.16). */
	@Test
	void B27_operators_nest_to_arbitrary_depth() {
		assertThat(eval("""
				{"all": [
				   {"any": [{"turnGte": 20}, {"gte": ["affinity.yuna", 30]}]},
				   {"not": {"all": [{"has": ["flags", "first_talk"]}, {"lt": ["affinity.yuna", 10]}]}}
				 ]}""")).isTrue();
	}

	/**
	 * 빈 배열은 표준 논리를 따른다 — {@code all: []} 는 참, {@code any: []} 는 거짓.
	 *
	 * <p>게이트에서 공허참이 위험해 보일 수 있으나 "조건 없음"은 컬럼을 {@code NULL} 로 두어
	 * 표현한다. 빈 배열은 잘못 만들어진 조건식이며, 표준과 다르게 동작시키면 그쪽이 더 놀랍다.
	 */
	@Test
	void B27_empty_arrays_follow_standard_logic() {
		assertThat(eval("""
				{"all": []}""")).isTrue();
		assertThat(eval("""
				{"any": []}""")).isFalse();
	}

	// ── 잘못된 조건식 ───────────────────────────────────────

	/** 미정의 연산자는 예외가 아니라 false 다. 오타 하나로 턴이 죽으면 안 된다. */
	@Test
	void B27_unknown_operator_is_false_not_an_exception() {
		assertThat(eval("""
				{"greaterThan": ["affinity.yuna", 10]}""")).isFalse();
	}

	/** 인자 모양이 틀려도 false 다. 추측해서 해석하지 않는다. */
	@Test
	void B27_malformed_operand_is_false() {
		assertThat(eval("""
				{"gte": "affinity.yuna"}""")).isFalse();
		assertThat(eval("""
				{"gte": ["affinity.yuna", "삼십"]}""")).isFalse();
		assertThat(eval("""
				{"all": {"gte": ["affinity.yuna", 10]}}""")).isFalse();
	}

	/** 연산자 하나짜리 객체가 아니면 해석하지 않는다. */
	@Test
	void B27_node_with_multiple_operators_is_false() {
		assertThat(eval("""
				{"gte": ["affinity.yuna", 10], "turnGte": 3}""")).isFalse();
	}

	/**
	 * §13-16 — {@code null} 조건은 평가기가 해석하지 않는다.
	 *
	 * <p>{@code chapter_def} 의 {@code NULL} 은 "진입 조건 없음"이고 {@code ending_def} 의
	 * {@code NULL} 은 기본 엔딩이다. 뜻이 다르므로 호출자(S-7)가 정한다.
	 */
	@Test
	void S13_16_null_condition_is_rejected_so_the_caller_decides() {
		assertThatThrownBy(() -> this.evaluator.evaluate(null, state()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> this.evaluator.evaluate(JSON.readTree("null"), state()))
				.isInstanceOf(IllegalArgumentException.class);
	}

	// ── 결정론 ──────────────────────────────────────────────

	/** I-15 · R11.7 — 같은 입력은 언제나 같은 결과다. */
	@Test
	void I15_same_condition_and_state_always_yield_the_same_result() {
		JsonNode condition = JSON.readTree("""
				{"all": [{"gte": ["affinity.yuna", 30]}, {"turnGte": 5}]}""");

		boolean first = this.evaluator.evaluate(condition, state());
		boolean second = this.evaluator.evaluate(condition, state());

		assertThat(first).isEqualTo(second).isTrue();
	}

	/** 평가기는 판정기이지 변경자가 아니다. 상태를 건드리면 S-7 의 순서가 무의미해진다. */
	@Test
	void B27_evaluation_does_not_mutate_the_state() {
		GameState before = state();

		this.evaluator.evaluate(JSON.readTree("""
				{"all": [{"gte": ["affinity.yuna", 30]}, {"has": ["flags", "first_talk"]}]}"""), before);

		assertThat(before).isEqualTo(state());
	}
}

package com.neowadaeum.play.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.play.engine.EndingEngine.EndingDecision;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-7 (#59) B-29 — 엔딩 판정이 <b>서버 단독</b>이고 <b>세션이 반드시 끝나는지</b> 확인한다.
 *
 * <p>§10.1-11 이 여기서 살아난다 — "어떤 조건도 안 걸릴 때 무한 진행되지 않는가".
 */
class EndingEngineTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final EndingEngine engine = new EndingEngine(new ConditionEvaluator());

	private static JsonNode condition(String json) {
		return JSON.readTree(json);
	}

	/** 조건부 2 + 기본 1. 기본 엔딩이 순회 <b>앞</b>에 있어도 가리지 않아야 한다. */
	private static List<EndingDefinition> endings() {
		return List.of(
				new EndingDefinition(1, "기본", null, false, true),
				new EndingDefinition(2, "이른 결심", condition("""
						{"gte": ["affinity.yuna", 10]}"""), false, false),
				new EndingDefinition(3, "첫 빛", condition("""
						{"gte": ["affinity.yuna", 30]}"""), false, false));
	}

	private static GameState stateWithAffinity(int affinity) {
		return GameState.from(JSON.readTree("""
				{"chapter": 3, "turn": 9, "affinity": {"yuna": %d}, "flags": [], "inventory": []}"""
				.formatted(affinity)));
	}

	/** R7.6 — {@code ending_no} 오름차순, 최초 매칭에서 종료한다. */
	@Test
	void R7_6_first_match_in_ending_no_order_wins() {
		EndingDecision decision = this.engine.decide(endings(), stateWithAffinity(50), false);

		assertThat(decision.reached()).isTrue();
		assertThat(decision.ending().endingNo()).isEqualTo(2);
		assertThat(decision.byDefault()).isFalse();
	}

	/**
	 * §13-16 규칙 1 — 기본 엔딩은 조건 순회에 <b>참여하지 않는다.</b>
	 *
	 * <p>여기서 기본 엔딩의 {@code ending_no} 는 1 이라 순회 맨 앞이다. 참여시키면 조건이 없으므로
	 * 항상 최초 매칭이 되고, <b>모든 세션이 1턴에 끝난다.</b>
	 */
	@Test
	void S13_16_default_ending_is_excluded_from_the_condition_sweep() {
		EndingDecision decision = this.engine.decide(endings(), stateWithAffinity(50), false);

		assertThat(decision.ending().defaultEnding()).isFalse();
		assertThat(decision.ending().endingNo()).isEqualTo(2);
	}

	/** R7.7 — 조건 미매칭이고 마지막 챕터가 아직 남았으면 종료하지 않는다. */
	@Test
	void R7_7_no_ending_while_the_last_chapter_still_has_turns() {
		EndingDecision decision = this.engine.decide(endings(), stateWithAffinity(0), false);

		assertThat(decision.reached()).isFalse();
		assertThat(decision.ending()).isNull();
	}

	/**
	 * §10.1-11 · R2.2 · R7.7 — 어떤 조건도 안 걸릴 때 무한히 진행되지 않는다.
	 *
	 * <p>필수 테스트의 문장을 그대로 옮긴 것이다. 마지막 챕터의 {@code max_turns} 에 도달하면
	 * 기본 엔딩으로 반드시 끝난다.
	 */
	@Test
	void S10_1_11_default_ending_prevents_an_endless_session() {
		EndingDecision decision = this.engine.decide(endings(), stateWithAffinity(0), true);

		assertThat(decision.reached()).isTrue();
		assertThat(decision.ending().defaultEnding()).isTrue();
		assertThat(decision.byDefault()).isTrue();
	}

	/** R7.7 — 마지막 챕터가 소진돼도 조건이 매칭되면 그쪽이 이긴다. 폴백은 최후다. */
	@Test
	void R7_7_condition_match_wins_over_the_fallback() {
		EndingDecision decision = this.engine.decide(endings(), stateWithAffinity(50), true);

		assertThat(decision.ending().endingNo()).isEqualTo(2);
		assertThat(decision.byDefault()).isFalse();
	}

	/**
	 * R2.2 — 폴백이 없으면 조용히 넘어가지 않는다.
	 *
	 * <p>하한(`>= 1`)은 DB 로 막을 수 없다 (§13-16 규칙 5, #54). 여기서 넘어가면 세션이 끝나지
	 * 못하고 §10.1-11 이 막으려던 상태가 그대로 발생한다.
	 */
	@Test
	void R2_2_missing_default_ending_fails_loudly() {
		List<EndingDefinition> withoutFallback = List.of(
				new EndingDefinition(1, "조건부", condition("""
						{"gte": ["affinity.yuna", 10]}"""), false, false));

		assertThatThrownBy(() -> this.engine.decide(withoutFallback, stateWithAffinity(0), true))
				.isInstanceOf(IllegalStateException.class);
	}

	// ── R7.11 시크릿 ────────────────────────────────────────

	/** R7.11 — 총계는 시크릿을 뺀다. 넣으면 미도달 엔딩의 존재가 드러난다. */
	@Test
	void R7_11_totals_exclude_secret_endings() {
		List<EndingDefinition> withSecret = List.of(
				new EndingDefinition(1, "기본", null, false, true),
				new EndingDefinition(2, "일반", condition("""
						{"gte": ["affinity.yuna", 10]}"""), false, false),
				new EndingDefinition(3, "시크릿", condition("""
						{"gte": ["affinity.yuna", 90]}"""), true, false));

		EndingDecision decision = this.engine.decide(withSecret, stateWithAffinity(20), false);

		assertThat(decision.totalEndings()).isEqualTo(2);
		assertThat(decision.endingIndex()).isEqualTo(2);
	}

	/**
	 * R7.11 — 시크릿 엔딩에 도달하면 {@code endingIndex} 는 {@code null} 이다.
	 *
	 * <p>비시크릿만 세는데 시크릿에 번호를 주면 총계와 어긋나고, "몇 번째"라는 표시가 미도달 엔딩의
	 * 존재를 드러낸다. <b>원문이 규정하지 않은 지점이며 기본 채택안이다</b> (#59 비고 나).
	 */
	@Test
	void R7_11_reaching_a_secret_ending_leaves_the_index_null() {
		List<EndingDefinition> withSecret = List.of(
				new EndingDefinition(1, "기본", null, false, true),
				new EndingDefinition(2, "시크릿", condition("""
						{"gte": ["affinity.yuna", 10]}"""), true, false));

		EndingDecision decision = this.engine.decide(withSecret, stateWithAffinity(20), false);

		assertThat(decision.reached()).isTrue();
		assertThat(decision.ending().secret()).isTrue();
		assertThat(decision.endingIndex()).isNull();
		assertThat(decision.totalEndings()).isEqualTo(1);
	}

	// ── 구조적 보장 ─────────────────────────────────────────

	/**
	 * R7.9 — AI 임의 종료를 <b>받을 자리가 없다.</b>
	 *
	 * <p>{@code endingSuggested} 를 인자로 받아 무시하는 구현은 다음 사람이 되살릴 수 있다.
	 */
	@Test
	void R7_9_engine_exposes_no_channel_for_ai_suggestions() {
		List<Method> judgements = Arrays.stream(EndingEngine.class.getDeclaredMethods())
				.filter(method -> method.getName().equals("decide"))
				.toList();

		assertThat(judgements).hasSize(1);
		assertThat(judgements.get(0).getParameterTypes())
				.containsExactly(List.class, GameState.class, boolean.class);
	}

	/** §13-16 · R2.11 — 잘못된 조합은 정의 단계에서 막는다. DB CHECK 와 같은 규칙이다. */
	@Test
	void S13_16_definition_rejects_contradictory_condition_and_default_combinations() {
		assertThatThrownBy(() -> new EndingDefinition(1, "기본인데 조건", condition("""
				{"gte": ["affinity.yuna", 1]}"""), false, true))
				.isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> new EndingDefinition(2, "조건 없는 일반", null, false, false))
				.isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> new EndingDefinition(3, "기본이며 시크릿", null, true, true))
				.isInstanceOf(IllegalArgumentException.class);
	}

	/** §10.1-1 · I-15 — 동일 GameState 는 항상 동일한 Ending 판정을 낸다. */
	@Test
	void S10_1_1_same_state_always_yields_the_same_ending_decision() {
		GameState state = stateWithAffinity(50);

		EndingDecision first = this.engine.decide(endings(), state, false);
		EndingDecision second = this.engine.decide(endings(), state, false);

		assertThat(first).isEqualTo(second);
	}

	/** 판정기는 상태를 바꾸지 않는다. */
	@Test
	void R7_6_decision_does_not_mutate_the_state() {
		GameState before = stateWithAffinity(50);

		this.engine.decide(endings(), before, true);

		assertThat(before).isEqualTo(stateWithAffinity(50));
	}
}

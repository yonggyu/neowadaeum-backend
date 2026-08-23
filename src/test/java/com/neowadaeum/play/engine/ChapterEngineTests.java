package com.neowadaeum.play.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.play.engine.ChapterEngine.ChapterDecision;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-7 (#59) B-28 — 챕터 전환이 <b>서버 단독 판정</b>인지 확인한다.
 *
 * <p>R7.2 의 평가 순서는 네 갈래로 갈린다 — {@code min_turns} 미충족 / 조건 만족 / 조건 불만족 +
 * {@code max_turns} 미도달 / 조건 불만족 + {@code max_turns} 도달. 네 갈래를 전부 고정한다.
 */
class ChapterEngineTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final ChapterEngine engine = new ChapterEngine(new ConditionEvaluator());

	/** 1장은 조건 없음, 2장은 호감도 10 이상, 3장은 호감도 20 이상. */
	private static List<ChapterDefinition> chapters() {
		return List.of(
				new ChapterDefinition(1, "첫날", null, 2, 4),
				new ChapterDefinition(2, "점심시간", condition("""
						{"gte": ["affinity.yuna", 10]}"""), 2, 4),
				new ChapterDefinition(3, "하교길", condition("""
						{"gte": ["affinity.yuna", 20]}"""), 1, 3));
	}

	private static JsonNode condition(String json) {
		return JSON.readTree(json);
	}

	private static GameState stateWithAffinity(int affinity) {
		return GameState.from(JSON.readTree("""
				{"chapter": 1, "turn": 3, "affinity": {"yuna": %d}, "flags": [], "inventory": []}"""
				.formatted(affinity)));
	}

	/** R7.2 — {@code min_turns} 를 채우기 전에는 조건이 만족돼도 넘어가지 않는다. */
	@Test
	void R7_2_chapter_does_not_advance_before_min_turns() {
		ChapterDecision decision = this.engine.decide(chapters(), 1, 1, stateWithAffinity(99));

		assertThat(decision.changed()).isFalse();
		assertThat(decision.chapterNo()).isEqualTo(1);
	}

	/** R7.2 — {@code min_turns} 충족 + 다음 챕터 조건 만족 → 전환. */
	@Test
	void R7_2_chapter_advances_when_the_next_entry_condition_holds() {
		ChapterDecision decision = this.engine.decide(chapters(), 1, 2, stateWithAffinity(10));

		assertThat(decision.changed()).isTrue();
		assertThat(decision.chapterNo()).isEqualTo(2);
		assertThat(decision.forced()).isFalse();
	}

	/** R7.2 — 조건 불만족 + {@code max_turns} 미도달 → 유지. */
	@Test
	void R7_2_chapter_stays_while_condition_fails_and_max_turns_is_not_reached() {
		ChapterDecision decision = this.engine.decide(chapters(), 1, 3, stateWithAffinity(0));

		assertThat(decision.changed()).isFalse();
		assertThat(decision.chapterNo()).isEqualTo(1);
	}

	/**
	 * R7.2 — {@code max_turns} 도달 시 조건과 무관하게 강제 전환한다 (B-28 DoD).
	 *
	 * <p>이것이 없으면 조건을 끝내 못 채운 플레이가 한 챕터에 갇혀 무한히 진행된다.
	 */
	@Test
	void R7_2_max_turns_forces_the_advance_even_when_the_condition_fails() {
		ChapterDecision decision = this.engine.decide(chapters(), 1, 4, stateWithAffinity(0));

		assertThat(decision.changed()).isTrue();
		assertThat(decision.chapterNo()).isEqualTo(2);
		assertThat(decision.forced()).isTrue();
	}

	/**
	 * 마지막 챕터에서는 전환하지 않는다 — 강제 전환도 없다.
	 *
	 * <p>갈 곳이 없는데 넘기면 존재하지 않는 챕터 번호가 생긴다. 여기서 끝나는 것은 챕터가 아니라
	 * 세션이며 그 판정은 {@link EndingEngine} 이 한다 (R7.7).
	 */
	@Test
	void R7_2_last_chapter_never_advances_even_past_max_turns() {
		ChapterDecision decision = this.engine.decide(chapters(), 3, 99, stateWithAffinity(99));

		assertThat(decision.changed()).isFalse();
		assertThat(decision.chapterNo()).isEqualTo(3);
	}

	/**
	 * §13-16 — {@code entry_condition = NULL} 은 "진입 조건 없음"이며 통과한다.
	 *
	 * <p>{@code ending_def} 의 {@code NULL} 과 뜻이 다르다. 그래서 {@link ConditionEvaluator} 는
	 * {@code null} 을 거부하고 해석을 호출자에게 맡긴다.
	 */
	@Test
	void S13_16_null_entry_condition_passes_as_no_condition() {
		List<ChapterDefinition> open = List.of(
				new ChapterDefinition(1, "1장", null, 1, 5),
				new ChapterDefinition(2, "2장", null, 1, 5));

		ChapterDecision decision = this.engine.decide(open, 1, 1, stateWithAffinity(0));

		assertThat(decision.changed()).isTrue();
		assertThat(decision.chapterNo()).isEqualTo(2);
		assertThat(decision.forced()).isFalse();
	}

	/**
	 * R7.1 — AI 제안값을 <b>받을 자리가 없다</b> (B-28 DoD "AI 제안값 무시").
	 *
	 * <p>{@code chapterAdvanceSuggested} 를 인자로 받아 무시하는 구현은 다음 사람이 되살릴 수 있다.
	 * 공개 판정 메서드가 하나이고 그 인자가 무엇인지를 테스트로 고정한다.
	 */
	@Test
	void R7_1_engine_exposes_no_channel_for_ai_suggestions() {
		List<Method> judgements = Arrays.stream(ChapterEngine.class.getDeclaredMethods())
				.filter(method -> method.getName().equals("decide"))
				.toList();

		assertThat(judgements).hasSize(1);
		assertThat(judgements.get(0).getParameterTypes())
				.containsExactly(List.class, int.class, int.class, GameState.class);
	}

	/** §10.1-1 · I-15 — 동일 GameState 는 항상 동일한 Chapter 판정을 낸다. */
	@Test
	void S10_1_1_same_state_always_yields_the_same_chapter_decision() {
		GameState state = stateWithAffinity(10);

		ChapterDecision first = this.engine.decide(chapters(), 1, 2, state);
		ChapterDecision second = this.engine.decide(chapters(), 1, 2, state);

		assertThat(first).isEqualTo(second);
	}

	/** 판정기는 상태를 바꾸지 않는다. 챕터 번호를 올리는 것은 서버 전용 경로다 (I-9). */
	@Test
	void R7_2_decision_does_not_mutate_the_state() {
		GameState before = stateWithAffinity(10);

		this.engine.decide(chapters(), 1, 2, before);

		assertThat(before).isEqualTo(stateWithAffinity(10));
		assertThat(before.chapter()).isEqualTo(1);
	}

	/** 정의에 없는 챕터로 판정을 요청하면 조용히 넘어가지 않는다. */
	@Test
	void R7_2_unknown_current_chapter_fails_loudly() {
		assertThatThrownBy(() -> this.engine.decide(chapters(), 9, 1, stateWithAffinity(0)))
				.isInstanceOf(IllegalArgumentException.class);
	}
}

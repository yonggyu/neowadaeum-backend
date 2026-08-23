package com.neowadaeum.play.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-5 (#55) — 서버가 상태의 최종 권한을 갖는지 확인한다.
 *
 * <p>여기서 지키는 것은 "값이 잘 병합되는가"가 아니라 <b>"AI 가 서버 권한을 넘겨받지 못하는가"</b>다.
 * §10.1 의 2 · 3 · 4 번이 이 클래스에서 처음으로 살아난다.
 *
 * <p>컨테이너가 필요 없다. 엔진은 순수 도메인이고 빠른 루프에서 돌아야 한다 (ADR-0001).
 */
class GameStateEngineTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** S-4 시드가 쓰는 것과 같은 구조다. 여기가 어긋나면 엔진이 실제 작품을 못 읽는다. */
	private static final String SCHEMA_JSON = """
			{
			  "affinity": { "yuna": { "min": 0, "max": 100, "maxDeltaPerTurn": 5 } },
			  "flags": ["met_yuna", "shared_lunch"],
			  "inventory": ["letter"]
			}""";

	private final GameStateEngine engine = new GameStateEngine();

	private static StateSchema schema() {
		return StateSchema.from(JSON.readTree(SCHEMA_JSON));
	}

	private static StateChanges changes(String json) {
		return StateChanges.from(JSON.readTree(json));
	}

	private static GameState stateAt(int chapter, int turn, int affinity) {
		return GameState.from(JSON.readTree("""
				{"chapter": %d, "turn": %d, "affinity": {"yuna": %d}, "flags": [], "inventory": []}"""
				.formatted(chapter, turn, affinity)));
	}

	// ── §10.1 필수 테스트 2 · 3 · 4 ─────────────────────────

	/**
	 * §10.1-2 · R4.2 — {@code affinity +100} 응답이 {@code +5} 로 잘리는가.
	 *
	 * <p>필수 테스트의 문장을 그대로 옮긴 것이다.
	 */
	@Test
	void S10_1_2_affinity_delta_of_one_hundred_is_clamped_to_five() {
		GameState result = this.engine.apply(stateAt(1, 3, 10), schema(), changes("""
				{"affinity.yuna": 100}"""));

		assertThat(result.numerics()).containsEntry("affinity.yuna", 15);
	}

	/** §10.1-3 · R4.1 — {@code state_schema} 에 없는 키가 무시되는가. */
	@Test
	void S10_1_3_key_absent_from_state_schema_is_ignored() {
		GameState result = this.engine.apply(stateAt(1, 3, 10), schema(), changes("""
				{"affinity.jaehyun": 5, "reputation.school": 30}"""));

		assertThat(result.numerics()).containsOnlyKeys("affinity.yuna");
		assertThat(result.numerics()).containsEntry("affinity.yuna", 10);
	}

	/**
	 * §10.1-4 · I-9 · R4.3 — AI 가 {@code chapter}/{@code turn} 을 반환해도 무시되는가.
	 *
	 * <p>서버 전용 필드다. 값이 반영되면 AI 가 인터스티셜을 임의로 띄우고 턴 번호를 흔들 수 있다.
	 */
	@Test
	void S10_1_4_ai_cannot_change_chapter_or_turn() {
		GameState result = this.engine.apply(stateAt(2, 7, 10), schema(), changes("""
				{"chapter": 99, "turn": 99, "affinity.yuna": 1}"""));

		assertThat(result.chapter()).isEqualTo(2);
		assertThat(result.turn()).isEqualTo(7);
		assertThat(result.numerics()).containsEntry("affinity.yuna", 11);
	}

	/**
	 * I-9 — {@code chapter}/{@code turn} 은 <b>담을 자리 자체가 없다.</b>
	 *
	 * <p>무시하는 코드는 다음 사람이 되살릴 수 있지만 없는 필드는 되살릴 수 없다.
	 */
	@Test
	void I9_state_changes_has_no_component_for_server_owned_fields() {
		List<String> components = Arrays.stream(StateChanges.class.getRecordComponents())
				.map(RecordComponent::getName).toList();

		assertThat(components).doesNotContain("chapter", "turn", "chapterNo", "turnNo");
	}

	// ── clamp 상세 (R4.2) ───────────────────────────────────

	/**
	 * R4.2 — 델타 상한과 값 범위를 <b>둘 다</b> 건다.
	 *
	 * <p>범위만 걸면 한 턴에 0 에서 100 으로 뛰는 것을 막지 못하고, 델타만 걸면 5 씩 쌓여 상한을 넘는다.
	 */
	@Test
	void R4_2_value_never_exceeds_max_even_after_repeated_max_deltas() {
		GameState state = stateAt(1, 1, 97);

		for (int turn = 0; turn < 3; turn++) {
			state = this.engine.apply(state, schema(), changes("""
					{"affinity.yuna": 5}"""));
		}

		assertThat(state.numerics()).containsEntry("affinity.yuna", 100);
	}

	/** R4.2 — 음수 델타도 같은 상한으로 잘린다. 한쪽만 막으면 하락으로 우회할 수 있다. */
	@Test
	void R4_2_negative_delta_is_capped_symmetrically() {
		GameState result = this.engine.apply(stateAt(1, 3, 50), schema(), changes("""
				{"affinity.yuna": -100}"""));

		assertThat(result.numerics()).containsEntry("affinity.yuna", 45);
	}

	/** R4.2 — 하한 아래로 내려가지 않는다. */
	@Test
	void R4_2_value_never_falls_below_min() {
		GameState result = this.engine.apply(stateAt(1, 3, 2), schema(), changes("""
				{"affinity.yuna": -5}"""));

		assertThat(result.numerics()).containsEntry("affinity.yuna", 0);
	}

	/** R4.2 — 선언하지 않으면 기본 델타 상한 ±5 를 쓴다. */
	@Test
	void R4_2_missing_delta_cap_falls_back_to_the_documented_default() {
		StateSchema loose = StateSchema.from(JSON.readTree("""
				{"affinity": {"yuna": {"min": 0, "max": 100}}}"""));

		GameState result = this.engine.apply(stateAt(1, 3, 10), loose, changes("""
				{"affinity.yuna": 40}"""));

		assertThat(result.numerics()).containsEntry("affinity.yuna", 15);
		assertThat(StateSchema.NumericSpec.DEFAULT_MAX_DELTA).isEqualTo(5);
	}

	/**
	 * 상태에 없던 수치는 {@code min} 을 기준으로 시작한다.
	 *
	 * <p>원문이 초기값을 규정하지 않아 내린 판단이다 — {@code state_schema} 에 기본값을 선언할
	 * 자리가 없다. {@code 0} 을 쓰면 {@code min} 이 양수인 스키마에서 범위 밖 값이 된다.
	 * 세션 시작 시의 정식 초기화는 S-9 범위다.
	 */
	@Test
	void R4_2_absent_numeric_starts_from_min_and_stays_in_range() {
		StateSchema shifted = StateSchema.from(JSON.readTree("""
				{"trust": {"ally": {"min": 10, "max": 20, "maxDeltaPerTurn": 5}}}"""));
		GameState empty = GameState.initial().advanceTo(1, 1);

		GameState result = this.engine.apply(empty, shifted, changes("""
				{"trust.ally": 3}"""));

		assertThat(result.numerics()).containsEntry("trust.ally", 13);
	}

	// ── §13-9 연산자 ────────────────────────────────────────

	/** §13-9 — 허용 연산자 일곱 가지가 전부 동작한다. */
	@Test
	void S13_9_documented_operators_are_applied() {
		GameState start = this.engine.apply(stateAt(1, 1, 0), schema(), changes("""
				{"flags.add": ["met_yuna", "shared_lunch"], "inventory.add": ["letter"]}"""));

		GameState result = this.engine.apply(start, schema(), changes("""
				{"flags.remove": ["shared_lunch"], "inventory.remove": ["letter"],
				 "location": "교실", "timeOfDay": "오후", "affinity.yuna": 3}"""));

		assertThat(result.flags()).containsExactly("met_yuna");
		assertThat(result.inventory()).isEmpty();
		assertThat(result.location()).isEqualTo("교실");
		assertThat(result.timeOfDay()).isEqualTo("오후");
		assertThat(result.numerics()).containsEntry("affinity.yuna", 3);
	}

	/** §13-9 — 그 외 키는 무시한다. 예외를 던지지 않는다. */
	@Test
	void S13_9_unknown_operator_is_ignored_without_failing_the_turn() {
		GameState result = this.engine.apply(stateAt(1, 1, 10), schema(), changes("""
				{"flags.toggle": ["met_yuna"], "affinity.yuna": 2}"""));

		assertThat(result.flags()).isEmpty();
		assertThat(result.numerics()).containsEntry("affinity.yuna", 12);
	}

	/** R4.1 — 선언되지 않은 플래그는 들어오지 않는다. */
	@Test
	void R4_1_undeclared_flag_is_not_merged() {
		GameState result = this.engine.apply(stateAt(1, 1, 0), schema(), changes("""
				{"flags.add": ["secret_route", "met_yuna"]}"""));

		assertThat(result.flags()).containsExactly("met_yuna");
	}

	/** R4.1 — 인벤토리를 선언하지 않은 작품에서는 아이템 추가가 전부 무시된다. */
	@Test
	void R4_1_inventory_is_closed_when_the_schema_declares_none() {
		StateSchema noInventory = StateSchema.from(JSON.readTree("""
				{"affinity": {"yuna": {"min": 0, "max": 100}}}"""));

		GameState result = this.engine.apply(stateAt(1, 1, 0), noInventory, changes("""
				{"inventory.add": ["letter"]}"""));

		assertThat(result.inventory()).isEmpty();
	}

	/** 제거는 화이트리스트를 묻지 않는다. 이미 있는 값을 빼는 것은 상태를 넓히지 않는다. */
	@Test
	void S13_9_removal_does_not_require_whitelist_membership() {
		GameState start = this.engine.apply(stateAt(1, 1, 0), schema(), changes("""
				{"flags.add": ["met_yuna"]}"""));

		GameState result = this.engine.apply(start, schema(), changes("""
				{"flags.remove": ["met_yuna", "never_existed"]}"""));

		assertThat(result.flags()).isEmpty();
	}

	// ── 결정론 · 직렬화 ─────────────────────────────────────

	/** I-15 · R11.7 — 같은 입력은 언제나 같은 출력이다. 난수가 개입할 자리가 없다. */
	@Test
	void I15_same_input_always_yields_the_same_output() {
		GameState base = stateAt(1, 4, 20);
		String proposal = """
				{"affinity.yuna": 3, "flags.add": ["met_yuna"], "location": "복도"}""";

		GameState first = this.engine.apply(base, schema(), changes(proposal));
		GameState second = this.engine.apply(base, schema(), changes(proposal));

		assertThat(first).isEqualTo(second);
	}

	/** I-5 — 엔진은 입력 상태를 바꾸지 않는다. 이전 상태가 살아 있어야 스냅샷이 append-only 다. */
	@Test
	void I5_engine_does_not_mutate_the_input_state() {
		GameState base = stateAt(1, 4, 20);

		this.engine.apply(base, schema(), changes("""
				{"affinity.yuna": 5, "flags.add": ["met_yuna"]}"""));

		assertThat(base.numerics()).containsEntry("affinity.yuna", 20);
		assertThat(base.flags()).isEmpty();
	}

	/** §4.1 — 직렬화하면 수치가 다시 중첩되고, 되읽으면 같은 상태가 된다. */
	@Test
	void S4_1_state_round_trips_through_its_json_form() {
		GameState state = this.engine.apply(stateAt(2, 6, 30), schema(), changes("""
				{"affinity.yuna": 4, "flags.add": ["met_yuna"], "location": "교문"}"""));

		JsonNode json = state.toJson();

		assertThat(json.path("affinity").path("yuna").asInt()).isEqualTo(34);
		assertThat(json.path("chapter").asInt()).isEqualTo(2);
		assertThat(json.path("turn").asInt()).isEqualTo(6);
		assertThat(GameState.from(json)).isEqualTo(state);
	}

	/** I-9 — 챕터·턴을 바꾸는 경로는 서버 전용 하나뿐이며 다른 값에 영향을 주지 않는다. */
	@Test
	void I9_only_the_server_path_advances_chapter_and_turn() {
		GameState state = this.engine.apply(stateAt(1, 3, 10), schema(), changes("""
				{"affinity.yuna": 2}"""));

		GameState advanced = state.advanceTo(2, 4);

		assertThat(advanced.chapter()).isEqualTo(2);
		assertThat(advanced.turn()).isEqualTo(4);
		assertThat(advanced.numerics()).isEqualTo(state.numerics());
	}
}

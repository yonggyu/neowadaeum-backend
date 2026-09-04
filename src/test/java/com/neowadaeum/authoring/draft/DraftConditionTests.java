package com.neowadaeum.authoring.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.catalog.publish.StoryDefinition;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.play.engine.ConditionEvaluator;
import com.neowadaeum.play.engine.GameState;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * #326 — <b>고른 조건 템플릿이 저장되고, 저장된 것이 실제로 평가된다</b> (R7.16, §13-69).
 *
 * <p><b>여기서만 확인할 수 있는 것.</b> 조립기가 만든 문자열이 조건 평가기가 <b>읽는 문법</b>인지는
 * 두 쪽을 나란히 놓아야 답해진다 — 한쪽만 보면 "저장은 된다"까지만 참이고, 그 조건은 런타임에
 * 조용히 거짓이 된다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class DraftConditionTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final ConditionEvaluator evaluator = new ConditionEvaluator();

	private static String payload(String chapterCondition, String endingCondition) {
		return """
				{"title":"봄의 학교","worldPrompt":"봄의 학교에서 시작한다.",
				 "characters":[{"name":"yuna"}],
				 "flags":["met_yuna"],
				 "chapters":[{"title":"1장"%s}],
				 "endings":[{"label":"좋은 끝"%s}]}
				""".formatted(chapterCondition, endingCondition);
	}

	private static String condition(String templateKey, String params) {
		return ",\"condition\":{\"templateKey\":\"%s\",\"params\":%s}".formatted(templateKey, params);
	}

	/**
	 * <b>고른 조건이 발행되는 정의에 실린다</b> (#326).
	 *
	 * <p>지금까지 챕터 조건은 늘 {@code null} 이었고 엔딩 조건은 늘 <b>도달 불가</b>였다 —
	 * 작성자가 고를 수 있는 것이 없었기 때문이다.
	 */
	@Test
	void S13_69_the_chosen_condition_reaches_the_published_definition() {
		StoryDefinition definition = DraftStoryDefinition
				.from(UUID.randomUUID(),
						payload(condition("turn_at_least", "{\"threshold\":3}"),
								condition("affinity_at_least",
										"{\"character\":\"yuna\",\"threshold\":50}")))
				.definition();

		assertThat(definition.chapters().getFirst().entryConditionJson())
				.isEqualTo("{\"turnGte\":3}");
		assertThat(definition.endings().getFirst().conditionJson())
				.isEqualTo("{\"gte\":[\"affinity.yuna\",50]}");
	}

	/**
	 * <b>조립된 것이 평가기가 읽는 문법이다</b> (#326).
	 *
	 * <p>조립기와 평가기가 갈라지면 저장은 성공하고 판정만 조용히 거짓이 된다 — 그 실패는
	 * <b>그 엔딩이 영원히 안 나온다</b>로만 나타나며 아무도 그것을 오류로 읽지 않는다.
	 */
	@Test
	void S13_69_every_template_assembles_into_something_the_evaluator_reads() {
		GameState state = new GameState(1, 7, null, null, Map.of("affinity.yuna", 60),
				Set.of("met_yuna"), List.of());

		assertThat(evaluate(assembled("affinity_at_least",
				"{\"character\":\"yuna\",\"threshold\":50}"), state)).isTrue();
		assertThat(evaluate(assembled("has_flag", "{\"flag\":\"met_yuna\"}"), state)).isTrue();
		assertThat(evaluate(assembled("lacks_flag", "{\"flag\":\"met_yuna\"}"), state)).isFalse();
		assertThat(evaluate(assembled("turn_at_least", "{\"threshold\":3}"), state)).isTrue();
	}

	/**
	 * <b>원고에 없는 인물을 가리키는 조건은 저장되지 않는다</b> (#326).
	 *
	 * <p>받아 두면 그 챕터·엔딩은 <b>영원히 도달되지 않고</b> 작성자는 그것을 알 길이 없다.
	 */
	@Test
	void S13_69_a_condition_pointing_at_an_undeclared_character_is_rejected() {
		assertThatThrownBy(() -> DraftStoryDefinition.validateConditions(
				payload(condition("affinity_at_least", "{\"character\":\"없는사람\",\"threshold\":50}"), "")))
				.isInstanceOf(ApiException.class);
	}

	/** 선언되지 않은 플래그도 같다 (#326). */
	@Test
	void S13_69_a_condition_pointing_at_an_undeclared_flag_is_rejected() {
		assertThatThrownBy(() -> DraftStoryDefinition
				.validateConditions(payload(condition("has_flag", "{\"flag\":\"없는플래그\"}"), "")))
				.isInstanceOf(ApiException.class);
	}

	/** <b>목록에 없는 템플릿 키를 조용히 무시하지 않는다</b> (#326). 무시하면 고른 조건이 사라진다. */
	@Test
	void S13_69_an_unknown_template_key_is_rejected() {
		assertThatThrownBy(() -> DraftStoryDefinition
				.validateConditions(payload(condition("affinity_at_most", "{}"), "")))
				.isInstanceOf(ApiException.class);
	}

	/** <b>문자열로 온 숫자를 받지 않는다</b> — 받으면 임계값의 형이 둘이 된다 (#326). */
	@Test
	void S13_69_a_threshold_that_is_not_an_integer_is_rejected() {
		assertThatThrownBy(() -> DraftStoryDefinition
				.validateConditions(payload(condition("turn_at_least", "{\"threshold\":\"3\"}"), "")))
				.isInstanceOf(ApiException.class);
	}

	/**
	 * <b>조건 없음은 오류가 아니다</b> (#326, §13-16).
	 *
	 * <p>작성 중인 원고는 아직 조건을 고르지 않았고, 기본 엔딩은 조건이 없어야 한다.
	 */
	@Test
	void S13_16_a_draft_without_conditions_still_saves() {
		assertThatCode(() -> DraftStoryDefinition.validateConditions(payload("", "")))
				.doesNotThrowAnyException();
	}

	/**
	 * <b>상태 스키마가 원고에서 나온다</b> (R4.1, #326).
	 *
	 * <p>상수 {@code {"flags":[]}} 로 발행하던 동안 어떤 호감도도 어떤 플래그도 병합 대상이
	 * 아니었고, 그래서 <b>고른 조건은 무엇이든 영원히 거짓</b>이었다.
	 */
	@Test
	void R4_1_the_state_schema_declares_what_the_draft_declared() {
		String schema = DraftStoryDefinition.from(UUID.randomUUID(), payload("", "")).stateSchema();

		var root = JSON.readTree(schema);
		assertThat(root.path("affinity").has("yuna")).isTrue();
		assertThat(root.path("affinity").path("yuna").path("maxDeltaPerTurn").asInt()).isEqualTo(5);
		assertThat(root.path("flags").valueStream().map(node -> node.asString()).toList())
				.containsExactly("met_yuna");
	}

	/** 조건을 고르지 않은 엔딩은 <b>도달 불가</b> 조건을 받는다 (§13-16). 폴백이 되면 첫 턴에 끝난다. */
	@Test
	void S13_16_an_ending_without_a_condition_stays_unreachable() {
		StoryDefinition definition = DraftStoryDefinition
				.from(UUID.randomUUID(), payload("", "")).definition();

		assertThat(definition.endings().getFirst().conditionJson()).isEqualTo("{\"turnGte\":1000000}");
		assertThat(definition.endings().getLast().isDefault()).isTrue();
		assertThat(definition.endings().getLast().conditionJson()).isNull();
	}

	/**
	 * <b>이름에 따옴표가 섞여도 조건식이 깨지지 않는다</b> (#326).
	 *
	 * <p>조립은 문자열 붙이기다 — 이스케이프를 손으로 하는 자리이므로 <b>영숫자 이름만
	 * 시험하면 지켜지지 않는 것을 지키는 척</b>하게 된다. 깨지면 조건식이 파싱되지 않아
	 * 그 챕터·엔딩은 영원히 도달되지 않고, 그 실패는 <b>아무 예외도 내지 않는다.</b>
	 */
	@Test
	void S13_69_a_name_with_quotes_still_assembles_into_valid_json() {
		String payload = """
				{"title":"봄의 학교","worldPrompt":"시작한다.",
				 "characters":[{"name":"유나\\"나쁜놈"}],
				 "flags":[],
				 "chapters":[{"title":"1장","condition":{"templateKey":"affinity_at_least",
				              "params":{"character":"유나\\"나쁜놈","threshold":50}}}],
				 "endings":[{"label":"좋은 끝"}]}
				""";

		String condition = DraftStoryDefinition.from(UUID.randomUUID(), payload).definition()
				.chapters().getFirst().entryConditionJson();

		GameState state = new GameState(1, 1, null, null,
				Map.of("affinity.유나\"나쁜놈", 60), Set.of(), List.of());
		assertThat(evaluate(condition, state)).isTrue();
	}

	/**
	 * <b>작성 중인 원고는 아직 아무것도 없다</b> (R8.3, #326).
	 *
	 * <p>1단계 저장은 제목 하나뿐일 수 있다. 조건 검증이 그것을 막으면 <b>작성을 시작할 수
	 * 없다.</b>
	 */
	@Test
	void R8_3_a_payload_with_nothing_in_it_yet_still_saves() {
		assertThatCode(() -> DraftStoryDefinition.validateConditions("{}"))
				.doesNotThrowAnyException();
		assertThatCode(() -> DraftStoryDefinition.validateConditions("{\"title\":\"봄의 학교\"}"))
				.doesNotThrowAnyException();
	}

	/**
	 * <b>읽을 수 없는 payload 는 400 이다</b> (#326).
	 *
	 * <p>그대로 던지면 500 이 되고, 그러면 작성자는 <b>서버가 고장 났다</b>고 읽는다 — 보낸
	 * 것이 JSON 이 아닌 것은 요청의 문제다.
	 */
	@Test
	void S9_1_an_unreadable_payload_is_a_validation_error() {
		assertThatThrownBy(() -> DraftStoryDefinition.validateConditions("이건 JSON 이 아니다"))
				.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> DraftStoryDefinition.validateConditions("[1,2,3]"))
				.isInstanceOf(ApiException.class);
	}

	private String assembled(String templateKey, String params) {
		return DraftStoryDefinition
				.from(UUID.randomUUID(), payload(condition(templateKey, params), ""))
				.definition().chapters().getFirst().entryConditionJson();
	}

	private boolean evaluate(String condition, GameState state) {
		return this.evaluator.evaluate(JSON.readTree(condition), state);
	}
}

package com.neowadaeum.authoring.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * #362 — <b>플래그 이름의 정본은 원고 {@code payload} 의 {@code flags} 다</b> (§13-73).
 *
 * <p><b>계약 안에서 어긋나 있었다.</b> {@code ConditionParams} 가 <i>"`flag` 는 이 원고의
 * `flags[]` 중 하나여야 한다"</i> 고 적었는데 {@code DraftPayload} 에 그 자리가 없었다 — 서버는
 * 이미 그 모양을 읽고 있었지만 <b>계약이 그 사실을 말하지 않아</b> 화면은 후보를 가질 수 없었고,
 * 그래서 조건 템플릿 넷 중 둘({@code has_flag} · {@code lacks_flag})은 <b>작성자가 고를 수
 * 없었다.</b>
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class DraftFlagDeclarationTests {

	private static String payload(String flags, String endingCondition) {
		return """
				{"title":"봄의 학교","settingDetail":"봄의 학교에서 시작한다.",
				 "characters":[{"name":"yuna"}],
				 "flags":%s,
				 "chapters":[{"title":"1장"}],
				 "endings":[{"label":"좋은 끝"%s}]}
				""".formatted(flags, endingCondition);
	}

	/** 발행된 {@code state_schema} 가 선언한 플래그 이름들. */
	private static List<String> flagsOf(String stateSchema) {
		List<String> names = new ArrayList<>();
		JsonMapper.builder().build().readTree(stateSchema).path("flags")
				.forEach(flag -> names.add(flag.asString()));
		return names;
	}

	private static String hasFlag(String name) {
		return ",\"conditionTemplateKey\":\"has_flag\",\"conditionParams\":{\"flag\":\"%s\"}"
				.formatted(name);
	}

	/**
	 * <b>선언한 이름을 가리키는 {@code has_flag} 가 저장되고 발행된다</b> (#362).
	 *
	 * <p>이것이 이슈가 막고 있던 것이다 — 후보를 줄 자리가 계약에 없어 화면이 그 템플릿을
	 * 잠갔고, 잠긴 템플릿은 <b>서버가 받아 준다는 사실</b>과 무관하게 쓸 수 없다.
	 */
	@Test
	void S13_73_a_declared_flag_can_be_pointed_at_by_a_condition() {
		var publishable = DraftStoryDefinition.from(UUID.randomUUID(),
				payload("[\"met_yuna\"]", hasFlag("met_yuna")));

		assertThat(publishable.definition().endings().getFirst().conditionJson())
				.isEqualTo("{\"has\":[\"flags\",\"met_yuna\"]}");
	}

	/**
	 * <b>선언한 이름이 {@code state_schema} 로 그대로 발행된다</b> — 인물과 같은 취급이다
	 * (R4.1, §13-69).
	 *
	 * <p>갈라지면 <b>검증을 통과한 조건이 런타임에 거짓</b>이 된다: 화이트리스트에 없는 플래그는
	 * 병합 대상이 아니므로 값이 영영 서지 않는다.
	 */
	@Test
	void S13_73_declared_flags_are_published_as_the_state_schema() {
		var publishable = DraftStoryDefinition.from(UUID.randomUUID(),
				payload("[\"met_yuna\",\"got_letter\"]", hasFlag("met_yuna")));

		// 순서는 단언하지 않는다 — 화이트리스트에 순서는 뜻이 없고, Set.copyOf 는 그것을
		// 보장하지도 않는다. 확인할 것은 **두 이름이 다 발행되는가** 다.
		assertThat(flagsOf(publishable.stateSchema()))
				.containsExactlyInAnyOrder("met_yuna", "got_letter");
	}

	/**
	 * <b>선언하지 않은 이름은 {@code 400} 이다.</b>
	 *
	 * <p>받아 두면 평가기에서 조용히 거짓이 되고 그 엔딩은 <b>영원히 도달되지 않는다</b> —
	 * 작성자는 그 사실을 알 길이 없다.
	 */
	@Test
	void S13_73_an_undeclared_flag_is_rejected() {
		assertThatThrownBy(() -> DraftStoryDefinition
				.validateConditions(payload("[\"met_yuna\"]", hasFlag("never_declared"))))
				.isInstanceOf(ApiException.class)
				.extracting("errorCode")
				.isEqualTo(ErrorCode.VALIDATION_ERROR);
	}

	/**
	 * <b>객체 배열은 조용히 비우지 않고 거절한다</b> (#362).
	 *
	 * <p>인물이 {@code [{"name": ...}]} 이므로 화면이 플래그도 같은 모양으로 보낼 수 있다.
	 * 그대로 두면 서버는 이름을 하나도 못 찾고, 작성자는 <b>플래그를 적었는데 그 이름이 없다고
	 * 한다</b> 를 보게 된다 — 무엇을 고쳐야 하는지 알 수 없는 상태다. 이 사이클에서 세 번 반복된
	 * 실패가 <b>조용히 빠진 값</b>이다 (§13-72).
	 */
	@Test
	void S13_73_flag_entries_that_are_not_strings_are_rejected() {
		assertThatThrownBy(() -> DraftStoryDefinition
				.validateConditions(payload("[{\"name\":\"met_yuna\"}]", "")))
				.isInstanceOf(ApiException.class);
	}

	/** 배열이 아닌 {@code flags} 도 같다 — 조용히 빈 목록이 되지 않는다. */
	@Test
	void S13_73_a_flags_field_that_is_not_an_array_is_rejected() {
		assertThatThrownBy(
				() -> DraftStoryDefinition.validateConditions(payload("\"met_yuna\"", "")))
				.isInstanceOf(ApiException.class);
	}

	/**
	 * <b>플래그가 없는 원고는 정상이다.</b> 조건을 아직 고르지 않았거나 쓰지 않을 수 있다 —
	 * 없는 것을 오류로 읽으면 <b>1단계 원고를 저장할 수 없다.</b>
	 */
	@Test
	void S13_73_a_draft_without_flags_still_saves() {
		assertThatCode(() -> DraftStoryDefinition
				.validateConditions("{\"title\":\"봄의 학교\",\"chapters\":[{\"title\":\"1장\"}]}"))
				.doesNotThrowAnyException();
	}

	/**
	 * <b>빈 항목은 건너뛴다</b> — 인물과 같다 (#350).
	 *
	 * <p>화면의 "추가" 버튼은 빈 줄을 먼저 만든다. 그것을 거절하면 <b>줄을 추가한 순간 저장이
	 * 막힌다.</b>
	 */
	@Test
	void S13_73_blank_entries_are_skipped_not_rejected() {
		var publishable = DraftStoryDefinition.from(UUID.randomUUID(),
				payload("[\"met_yuna\",\"\",\"  \"]", hasFlag("met_yuna")));

		assertThat(flagsOf(publishable.stateSchema())).containsExactly("met_yuna");
	}

	/**
	 * <b>개수 상한</b> (§13-73, {@code [결정 필요]} — 기본 채택안 32).
	 *
	 * <p>상한을 두는 이유는 저장소가 아니라 <b>프롬프트</b>다 — 플래그는 한 번 서면 매 턴
	 * {@code GAME_STATE} 레이어에 실려 나간다.
	 */
	@Test
	void S13_73_more_flags_than_the_cap_is_rejected() {
		String tooMany = IntStream.rangeClosed(0, DraftStateSchema.MAX_FLAGS)
				.mapToObj("\"flag_%d\""::formatted)
				.collect(Collectors.joining(",", "[", "]"));

		assertThatThrownBy(() -> DraftStoryDefinition.validateConditions(payload(tooMany, "")))
				.isInstanceOf(ApiException.class);
	}

	/** 길이 상한 (§13-73, {@code [결정 필요]} — 기본 채택안 40자). 같은 이유로 둔다. */
	@Test
	void S13_73_a_flag_name_longer_than_the_cap_is_rejected() {
		String tooLong = "\"" + "가".repeat(DraftStateSchema.MAX_FLAG_LENGTH + 1) + "\"";

		assertThatThrownBy(
				() -> DraftStoryDefinition.validateConditions(payload("[" + tooLong + "]", "")))
				.isInstanceOf(ApiException.class);
	}
}

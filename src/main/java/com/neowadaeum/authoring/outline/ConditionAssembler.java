package com.neowadaeum.authoring.outline;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 고른 템플릿과 값을 <b>조건식으로 조립한다</b> (R7.16, §13-56, #326).
 *
 * <p><b>조립이 서버에 있는 이유.</b> 클라이언트가 만든 구조를 그대로 평가기에 먹이면 그것이 곧
 * DSL 입력면이 된다 — I-1 이 {@code choiceId} 에 대해 세운 것과 같다. R7.16 이 정한 것은
 * <b>템플릿 선택만</b>이고, 그 선택에서 조건식으로 가는 길은 여기 하나다.
 *
 * <p><b>모양은 {@link com.neowadaeum.play.engine.ConditionEvaluator} 가 읽는 것 그대로다.</b>
 * 평가기를 고치지 않는다 (#326) — 이미 있는 문법에 값을 끼워 넣을 뿐이다.
 *
 * <p><b>이름은 원고가 선언한 것이어야 한다.</b> 없는 인물·플래그를 가리키는 조건은 평가기에서
 * 조용히 거짓이 되고, 그 챕터·엔딩은 <b>영원히 도달되지 않는다</b> — 작성자는 그것을 알 길이
 * 없으므로 <b>저장을 거절하는 편이 친절하다.</b>
 */
public final class ConditionAssembler {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private ConditionAssembler() {
	}

	/**
	 * @param schema 원고가 선언한 인물·플래그 이름
	 * @return 평가기가 읽는 조건식 JSON
	 * @throws ApiException {@code VALIDATION_ERROR} — 값이 빠졌거나, 형이 다르거나, 원고에
	 *     선언되지 않은 이름을 가리킨다
	 */
	public static String assemble(ConditionSelection selection, Set<String> characters,
			Set<String> flags) {
		JsonNode params = selection.params();
		return switch (selection.template()) {
			case AFFINITY_AT_LEAST -> {
				String character = name(params, "character", characters);
				yield "{\"gte\":[\"affinity.%s\",%d]}".formatted(character,
						threshold(params, "threshold"));
			}
			case HAS_FLAG -> "{\"has\":[\"flags\",\"%s\"]}".formatted(name(params, "flag", flags));
			case LACKS_FLAG ->
				"{\"not\":{\"has\":[\"flags\",\"%s\"]}}".formatted(name(params, "flag", flags));
			case TURN_AT_LEAST -> "{\"turnGte\":%d}".formatted(threshold(params, "threshold"));
		};
	}

	/**
	 * 선언된 이름 하나.
	 *
	 * <p><b>선언 목록이 비어 있으면 이 템플릿은 쓸 수 없다.</b> 그것이 사실이다 — 인물을 한 명도
	 * 적지 않은 원고에서 호감도 조건은 뜻을 갖지 못한다.
	 */
	private static String name(JsonNode params, String field, Set<String> declared) {
		String value = params.path(field).asString(null);
		if (value == null || value.isBlank() || !declared.contains(value)) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
		// 조립은 문자열 붙이기다 — 이름에 따옴표가 섞이면 조건식이 깨진다. 선언 목록에 있는
		// 값만 오므로 여기까지 오는 값은 작성자가 자기 원고에 적은 이름이지만, 그 이름 자체가
		// 따옴표를 담고 있을 수 있다.
		return JSON.writeValueAsString(value).replaceAll("^\"|\"$", "");
	}

	/** 정수 하나. <b>문자열로 온 숫자를 받아 주지 않는다</b> — 받으면 형이 둘이 된다. */
	private static long threshold(JsonNode params, String field) {
		JsonNode value = params.path(field);
		if (!value.isIntegralNumber()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
		return value.asLong();
	}
}

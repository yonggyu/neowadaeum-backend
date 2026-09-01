package com.neowadaeum.ai.provider;

import com.neowadaeum.ai.schema.OutlineOutputSchemaException;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 초안 응답의 형식 (B-52).
 *
 * <p><b>어댑터마다 따로 읽지 않는다.</b> {@link SafetyClassificationFormat} 과 같은 이유다 —
 * 형식은 {@code PlatformPrompts.OUTLINE} 이 지시한 것 하나이고, 그것을 읽는 코드가 벤더별로
 * 갈리면 <b>한쪽만 관대해진다.</b> 벤더가 다른 것은 응답 <b>봉투</b>이지 이 안쪽이 아니다.
 *
 * <h2>무엇이 계약 위반인가</h2>
 *
 * <p><b>형태가 위반이고 개수는 위반이 아니다.</b>
 *
 * <ul>
 * <li>JSON 이 아니다 · 객체가 아니다 · {@code chapters}/{@code endings} 가 배열이 아니다 → <b>위반</b></li>
 * <li>항목이 객체가 아니다 · 이름({@code title}/{@code label})이 비어 있다 → <b>위반</b></li>
 * <li>요청보다 <b>적게</b> 왔다 → 위반이 아니다. 그대로 담는다 (#238)</li>
 * </ul>
 *
 * <p><b>이름이 없는 항목을 통과시키지 않는 이유.</b> 목록에 보일 것이 없는 초안은 작성자 화면에서
 * <b>빈 줄</b>이 된다. 그것은 "AI 가 제안하지 않았다"가 아니라 "AI 가 빈 것을 제안했다"로 읽힌다.
 *
 * <p><b>요청보다 많이 오면 자른다.</b> 서버가 개수를 정하고(R7.14) 일일 상한이 그 개수를 전제로
 * 비용을 계산한다 (R8.12) — 모델이 더 주는 것을 그대로 받으면 그 전제가 깨진다.
 */
public final class OutlineOutputFormat {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private OutlineOutputFormat() {
	}

	/**
	 * {@code {"chapters":[{"title","summary"}],"endings":[{"label","epilogue"}]}} 를 읽는다.
	 *
	 * @param request 요청 — 상한을 자를 때만 쓴다. <b>하한으로 쓰지 않는다</b>
	 */
	public static OutlineResult parse(String text, OutlineRequest request) {
		JsonNode parsed;
		try {
			parsed = JSON.readTree(text);
		}
		catch (RuntimeException ex) {
			// 원문을 메시지에 담지 않는다 (S-3).
			throw new OutlineOutputSchemaException("outline response is not json", ex);
		}
		if (!parsed.isObject()) {
			throw new OutlineOutputSchemaException("outline response is not a json object");
		}

		List<OutlineResult.Chapter> chapters = new ArrayList<>();
		for (JsonNode node : array(parsed, "chapters", request.chapterCount())) {
			chapters.add(new OutlineResult.Chapter(
					required(node, "title", "chapters"), optional(node, "summary")));
		}

		List<OutlineResult.Ending> endings = new ArrayList<>();
		for (JsonNode node : array(parsed, "endings", request.endingCount())) {
			endings.add(new OutlineResult.Ending(
					required(node, "label", "endings"), optional(node, "epilogue")));
		}
		return new OutlineResult(chapters, endings);
	}

	/** 배열이 아니면 위반이다. <b>없는 것도 위반</b> — 빈 배열과 빠뜨린 것은 다르다. */
	private static List<JsonNode> array(JsonNode parsed, String field, int limit) {
		JsonNode node = parsed.path(field);
		if (!node.isArray()) {
			throw new OutlineOutputSchemaException("outline response has no " + field + " array");
		}

		List<JsonNode> items = new ArrayList<>();
		for (JsonNode item : node) {
			if (items.size() == limit) {
				break;
			}
			if (!item.isObject()) {
				throw new OutlineOutputSchemaException("outline " + field + " item is not an object");
			}
			items.add(item);
		}
		return items;
	}

	private static String required(JsonNode node, String field, String where) {
		String value = node.path(field).asString(null);
		if (value == null || value.isBlank()) {
			throw new OutlineOutputSchemaException("outline " + where + " item has no " + field);
		}
		return value.strip();
	}

	/** 없으면 {@code null} 이다 — 빈 문자열은 <b>비어 있는 글</b>로 읽힌다. */
	private static String optional(JsonNode node, String field) {
		String value = node.path(field).asString(null);
		return (value == null || value.isBlank()) ? null : value.strip();
	}
}

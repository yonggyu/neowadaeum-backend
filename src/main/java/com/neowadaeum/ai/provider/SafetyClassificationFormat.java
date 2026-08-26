package com.neowadaeum.ai.provider;

import com.neowadaeum.common.spi.SafetyCategory;
import com.neowadaeum.common.spi.SafetyClassificationFailedException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 판정 응답의 형식 (B-30).
 *
 * <p><b>어댑터마다 따로 읽지 않는다.</b> 형식은 {@code PlatformPrompts.SAFETY_JUDGE} 가 지시한
 * 것 하나이고, 그것을 읽는 코드가 벤더별로 갈리면 <b>한쪽만 관대해진다</b> — 관대해진 쪽이
 * 판정 실패를 통과로 바꾼다. 벤더가 다른 것은 응답 <b>봉투</b>이지 이 안쪽이 아니다.
 *
 * <p><b>모든 실패가 {@link SafetyClassificationFailedException} 이다.</b> 형식이 어긋났다는 것은
 * "무엇을 봤는지 모른다"는 뜻이며, 모르는 것은 통과가 아니다 (fail-closed).
 */
public final class SafetyClassificationFormat {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private SafetyClassificationFormat() {
	}

	/** {@code {"categories": [...]}} 를 읽는다. 걸린 것이 없으면 빈 집합이다. */
	public static Set<SafetyCategory> parse(String text) {
		JsonNode parsed;
		try {
			parsed = JSON.readTree(text);
		}
		catch (RuntimeException ex) {
			// 원문을 메시지에 담지 않는다 (S-3).
			throw new SafetyClassificationFailedException("classification response is not json");
		}

		JsonNode categories = parsed.path("categories");
		if (!categories.isArray()) {
			throw new SafetyClassificationFailedException("classification response has no categories array");
		}

		Set<SafetyCategory> hits = new LinkedHashSet<>();
		for (JsonNode category : categories) {
			hits.add(SafetyCategory.fromWireValue(category.asString(null)));
		}
		return hits;
	}

	/** {@code ai_call_log.safety_flags} 에 남길 표기 (R9.3). 걸린 것이 없으면 남기지 않는다. */
	public static String flags(Set<SafetyCategory> categories) {
		if (categories.isEmpty()) {
			return null;
		}
		return categories.stream().map(SafetyCategory::wireValue).collect(Collectors.joining(","));
	}
}

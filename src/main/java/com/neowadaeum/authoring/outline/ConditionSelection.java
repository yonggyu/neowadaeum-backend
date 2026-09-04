package com.neowadaeum.authoring.outline;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import java.util.Locale;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * 작성자가 고른 조건 하나 — <b>템플릿 키와 값들</b> (R7.16, §13-56, #326).
 *
 * <p><b>클라이언트가 조건식 JSON 을 보내지 않는다.</b> 보내게 하면 그것이 곧 조건 평가기의
 * 입력면이 되고, 쓸 수 있는 사람이 <b>미정의 동작</b>을 찾아낸다 — I-1 이 {@code choiceId} 에
 * 대해 세운 것과 같은 이유다. 오는 것은 <b>고른 것</b>이고, 조립은 서버가 한다
 * ({@link ConditionAssembler}).
 *
 * <p><b>키만으로는 조건이 완성되지 않는다</b> (#282). {@code affinity_at_least} 하나를 저장하려면
 * <b>어떤 인물</b>과 <b>얼마</b>가 함께 와야 하며, 무엇을 물어야 하는지는 템플릿이 스스로
 * 선언한다 ({@link ConditionTemplate#parameters()}).
 */
public record ConditionSelection(ConditionTemplate template, JsonNode params) {

	/**
	 * 계약이 이미 이 이름을 쓴다 (#354, §13-56).
	 *
	 * <p>{@code OutlineChapter} · {@code OutlineEnding} 이 초안 응답에서 이 키를 돌려주므로,
	 * 저장할 때 <b>다른 이름으로 접어 넣으면 한 자리에 두 표기가 생긴다</b> — 화면은 응답에서
	 * 받은 것을 그대로 되돌려 보낼 수 없게 되고, 그 변환이 어느 날 하나를 빠뜨린다.
	 */
	private static final String TEMPLATE_KEY = "conditionTemplateKey";

	/** 키가 접고 있지 않은 값들 (#282). 형제 필드다 — 키와 같은 높이에 있다. */
	private static final String PARAMS = "conditionParams";

	/**
	 * 원고 {@code payload} 의 챕터·엔딩 한 항목에서 <b>고른 조건</b>을 읽는다.
	 *
	 * <p><b>중첩 객체가 아니라 형제 필드다</b> (#354). 초안 응답이 {@code conditionTemplateKey}
	 * 를 그 높이에 돌려주므로, 저장이 그것을 한 겹 접으면 화면은 <b>받은 모양과 보내는 모양이
	 * 다른</b> 상태를 매번 변환해야 한다.
	 *
	 * @return 조건을 고르지 않았으면 비어 있다. <b>조건 없음은 오류가 아니다</b> — 챕터는
	 *     조건 없이 이어질 수 있고, 기본 엔딩은 조건이 없어야 한다 (§13-16)
	 * @throws ApiException {@code VALIDATION_ERROR} — 자리는 있는데 템플릿 키가 없거나 목록에
	 *     없는 키다. <b>모르는 키를 조용히 무시하지 않는다</b>: 무시하면 작성자가 고른 조건이
	 *     사라진 채 발행되고, 그 챕터·엔딩은 <b>영원히 도달되지 않는다</b>
	 */
	public static Optional<ConditionSelection> read(JsonNode item) {
		if (item == null || !item.isObject()) {
			return Optional.empty();
		}
		JsonNode key = item.path(TEMPLATE_KEY);
		// **고르지 않은 것과 비운 것을 같게 본다.** 화면은 조건을 지울 때 `null` 을 보내고,
		// 아직 고르지 않은 항목에는 키 자체가 없다 — 둘 다 "조건 없음"이 사실이다.
		if (key.isNull() || key.isMissingNode()) {
			return Optional.empty();
		}
		String value = key.asString(null);
		if (value == null || value.isBlank()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
		return Optional.of(new ConditionSelection(templateOf(value), item.path(PARAMS)));
	}

	private static ConditionTemplate templateOf(String key) {
		for (ConditionTemplate template : ConditionTemplate.values()) {
			if (template.key().equals(key.toLowerCase(Locale.ROOT))) {
				return template;
			}
		}
		// 목록은 GET /authoring/metadata 가 선언한다 (§13-56). 거기 없는 키는 평가기가
		// 지원하지 않는 조건이며, 받아 두면 조용히 false 가 된다.
		throw new ApiException(ErrorCode.VALIDATION_ERROR);
	}
}

package com.neowadaeum.ai.schema;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 원시 응답을 §5.2 의 출력 스키마로 읽는다 (B-21, R5.1 ~ R5.4).
 *
 * <p><b>어떤 규칙을 여기서 강제하고 어떤 규칙을 강제하지 않는지가 이 클래스의 설계다.</b>
 *
 * <table border="1">
 * <caption>§5.2 규칙별 처리</caption>
 * <tr><th>규칙</th><th>성격</th><th>여기서 하는 일</th></tr>
 * <tr><td>R5.1 본문은 {@code paragraphs[]}</td><td>구조</td>
 *     <td><b>거부</b> — 통 문자열을 읽지 않는다</td></tr>
 * <tr><td>R5.2 {@code speakerName} nullable</td><td>구조</td>
 *     <td>통과. 키가 없어도 같다</td></tr>
 * <tr><td>R5.3 문단 3~5개 · 120자</td><td><b>프롬프트가 강제</b></td>
 *     <td><b>거부하지 않는다.</b> 빈 배열만 R5.1 로 거부한다</td></tr>
 * <tr><td>R5.4 선택지 1~4개</td><td><b>서버 행위 지정</b></td>
 *     <td>4 초과는 <b>절단</b>, 0 은 <b>거부</b></td></tr>
 * </table>
 *
 * <p><b>R5.3 을 거부 조건으로 만들지 않는 이유.</b> 원문이 "프롬프트로 강제"라고 주체를 지정했고,
 * {@code OUTPUT_SPEC} 레이어가 이미 그 문장을 싣고 있다 (B-20). 서버가 여기에 더해 거부까지 하면
 * <b>문단이 6개인 정상 본문이 25초짜리 재생성을 부른다.</b> 길이는 취향이고 구조는 계약이다.
 *
 * <p><b>R5.4 를 절단과 거부로 나눈 근거.</b> 원문은 "범위를 벗어나면 서버가 <b>절단하거나 재요청</b>"
 * 이라고 두 처리를 함께 적는다. 상한 초과는 <b>버릴 것이 있으므로</b> 절단이 성립하고, 하한 미달은
 * 버릴 것이 없으므로 재요청밖에 없다. 선택지 0개는 사용자가 아무것도 못 하는 화면이다.
 *
 * <p><b>모르는 필드는 무시한다.</b> 응답에 {@code chapter} 나 {@code turn} 이 실려 와도
 * {@link TurnOutput} 에 담을 자리가 없어 그대로 버려진다 (I-9). 그것을 <b>거부 사유로 삼지 않는</b>
 * 것은, 모델이 덧붙인 필드 하나가 턴 전체를 날리는 것이 I-9 가 요구하는 바가 아니기 때문이다 —
 * I-9 가 요구하는 것은 "서버가 그 값을 읽지 않는 것"이고, 담을 자리가 없다는 사실이 그것을 보장한다.
 *
 * <p><b>S-3 — 예외에 응답 원문을 넣지 않는다.</b> 어긋난 <b>지점</b>까지만 남긴다.
 */
public class TurnOutputParser {

	/** 선택지 상한 (R5.4). 초과분은 절단한다. */
	static final int MAX_CHOICES = 4;

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * @param raw Provider 응답 원문
	 * @return §5.2 형태로 읽고 정규화한 출력
	 * @throws TurnOutputSchemaException JSON 이 아니거나 스키마를 만족하지 않을 때
	 */
	public TurnOutput parse(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new TurnOutputSchemaException("response body is empty");
		}

		JsonNode root = readTree(raw);
		if (!root.isObject()) {
			throw new TurnOutputSchemaException("response root must be a JSON object");
		}

		return new TurnOutput(
				speakerName(root),
				paragraphs(root),
				choices(root),
				stateChanges(root),
				chapterAdvanceSuggested(root),
				endingSuggested(root));
	}

	private JsonNode readTree(String raw) {
		try {
			return JSON.readTree(raw);
		}
		catch (JacksonException ex) {
			// 원문을 메시지에 넣지 않는다 (S-3). 원인 예외에도 값이 실릴 수 있어 붙이지 않는다.
			throw new TurnOutputSchemaException("response is not valid JSON");
		}
	}

	/** R5.2 — 없어도 되고 {@code null} 이어도 된다. 빈 문자열은 나레이션과 같은 뜻으로 본다. */
	private String speakerName(JsonNode root) {
		JsonNode node = root.get("speakerName");
		if (node == null || node.isNull()) {
			return null;
		}
		if (!node.isString()) {
			throw new TurnOutputSchemaException("speakerName must be a string or null");
		}
		String value = node.stringValue().trim();
		return value.isEmpty() ? null : value;
	}

	/** R5.1 — 배열이어야 하고 비어 있으면 본문이 없는 것이다. */
	private List<TurnOutput.Paragraph> paragraphs(JsonNode root) {
		JsonNode node = root.get("paragraphs");
		if (node == null || !node.isArray()) {
			// 통 문자열이 여기로 온다. R5.1 이 금지한 형태다.
			throw new TurnOutputSchemaException("paragraphs must be an array");
		}
		if (node.isEmpty()) {
			throw new TurnOutputSchemaException("paragraphs must not be empty");
		}

		List<TurnOutput.Paragraph> paragraphs = new ArrayList<>();
		for (JsonNode element : node) {
			paragraphs.add(paragraph(element));
		}
		return paragraphs;
	}

	private TurnOutput.Paragraph paragraph(JsonNode element) {
		if (!element.isObject()) {
			throw new TurnOutputSchemaException("each paragraph must be an object");
		}
		return new TurnOutput.Paragraph(paragraphType(element.get("type")), text(element.get("text"), "paragraph"));
	}

	private TurnOutput.ParagraphType paragraphType(JsonNode node) {
		if (node == null || !node.isString()) {
			throw new TurnOutputSchemaException("paragraph type must be a string");
		}
		try {
			return TurnOutput.ParagraphType.valueOf(node.stringValue().trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			// 이름은 남긴다 — 모델이 만들어 낸 종류가 무엇인지가 프롬프트를 고치는 단서다.
			// 본문 텍스트가 아니라 열거형 후보이므로 S-3 의 대상이 아니다.
			throw new TurnOutputSchemaException("unknown paragraph type: " + node.stringValue().trim());
		}
	}

	/**
	 * R5.4 — 1~4개. 4 초과는 앞에서부터 절단하고, 0개는 거부한다.
	 *
	 * <p><b>{@code order} 중복을 허용하지 않는다.</b> 같은 순서가 둘이면 화면에서 어느 것이 위인지
	 * 정해지지 않고, {@code choiceId} 가 그 좌표로 발급되므로 (§13-9) 대조 단계까지 모호함이 번진다.
	 */
	private List<TurnOutput.Choice> choices(JsonNode root) {
		JsonNode node = root.get("choices");
		if (node == null || !node.isArray()) {
			throw new TurnOutputSchemaException("choices must be an array");
		}
		if (node.isEmpty()) {
			// 절단할 것이 없다. 선택지 0개는 사용자가 아무것도 못 하는 화면이다.
			throw new TurnOutputSchemaException("choices must not be empty");
		}

		List<TurnOutput.Choice> choices = new ArrayList<>();
		Set<Integer> orders = new HashSet<>();
		for (JsonNode element : node) {
			TurnOutput.Choice choice = choice(element);
			if (!orders.add(choice.order())) {
				throw new TurnOutputSchemaException("duplicate choice order: " + choice.order());
			}
			choices.add(choice);
		}

		choices.sort(Comparator.comparingInt(TurnOutput.Choice::order));
		return choices.size() > MAX_CHOICES ? List.copyOf(choices.subList(0, MAX_CHOICES)) : choices;
	}

	private TurnOutput.Choice choice(JsonNode element) {
		if (!element.isObject()) {
			throw new TurnOutputSchemaException("each choice must be an object");
		}
		JsonNode order = element.get("order");
		if (order == null || !order.isIntegralNumber() || order.intValue() < 1) {
			throw new TurnOutputSchemaException("choice order must be an integer starting at 1");
		}
		return new TurnOutput.Choice(order.intValue(), text(element.get("text"), "choice"));
	}

	/** 본문·선택지 텍스트의 공통 조건. 값은 메시지에 넣지 않는다 (S-3). */
	private String text(JsonNode node, String owner) {
		if (node == null || !node.isString() || node.stringValue().isBlank()) {
			throw new TurnOutputSchemaException(owner + " text must be a non-blank string");
		}
		return node.stringValue();
	}

	/**
	 * 상태 변화 제안. <b>원시 JSON 그대로 넘긴다.</b>
	 *
	 * <p>여기서 값을 검사하지 않는 것은 게으름이 아니다 — 화이트리스트와 clamp 는 GameState 엔진이
	 * 소유한 규칙이며(R4.1, R4.2), 파서가 미리 걸러 내면 <b>같은 규칙이 두 곳에 생기고</b> 둘이
	 * 갈라지는 날 어느 쪽이 진실인지 알 수 없게 된다. 여기서 보는 것은 객체인가까지다.
	 */
	private JsonNode stateChanges(JsonNode root) {
		JsonNode node = root.get("stateChanges");
		if (node == null || node.isNull()) {
			return JSON.createObjectNode();
		}
		if (!node.isObject()) {
			throw new TurnOutputSchemaException("stateChanges must be an object");
		}
		return node;
	}

	/** R5.7 — 제안값이다. 없으면 전환을 제안하지 않은 것으로 본다. */
	private boolean chapterAdvanceSuggested(JsonNode root) {
		JsonNode node = root.get("chapterAdvanceSuggested");
		if (node == null || node.isNull()) {
			return false;
		}
		if (!node.isBoolean()) {
			throw new TurnOutputSchemaException("chapterAdvanceSuggested must be a boolean");
		}
		return node.booleanValue();
	}

	/** R5.7 · R7.9 — 제안값이다. 조건이 매칭되지 않으면 엔딩 엔진이 무시한다. */
	private String endingSuggested(JsonNode root) {
		JsonNode node = root.get("endingSuggested");
		if (node == null || node.isNull()) {
			return null;
		}
		if (!node.isString()) {
			throw new TurnOutputSchemaException("endingSuggested must be a string or null");
		}
		String value = node.stringValue().trim();
		return value.isEmpty() ? null : value;
	}
}

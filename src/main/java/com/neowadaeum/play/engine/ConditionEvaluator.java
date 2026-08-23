package com.neowadaeum.play.engine;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * GameState 참조식을 평가한다 (B-27 / S-6, R7.4).
 *
 * <p><b>I-10 이 성립하는 자리다.</b> 챕터 전환과 엔딩 선언은 서버가 GameState 로 판정하며, 그 판정의
 * 계산기가 이 클래스다. AI 의 {@code chapterAdvanceSuggested} · {@code endingSuggested} 는 여기에
 * 입력되지 않는다.
 *
 * <p><b>I-15 · R11.7 — 난수가 없다.</b> 같은 {@code (조건식, GameState)} 는 언제나 같은 결과다.
 * 시각·요청 ID·해시 같은 외부 값도 읽지 않는다.
 *
 * <p><b>미정의 키는 {@code false} 다.</b> 예외를 던지면 작품 데이터의 오타 하나가 턴 전체를 죽인다.
 * 대신 <b>무시했다는 사실은 남긴다</b> — 조용히 {@code false} 를 돌려주면 "챕터가 안 넘어간다"의
 * 원인을 찾을 수 없다.
 *
 * <p>지원 연산자 (`docs/tasks.md` B-27)
 *
 * <pre>
 * {"all":     [조건, …]}        전부 참        (빈 배열 → true)
 * {"any":     [조건, …]}        하나라도 참    (빈 배열 → false)
 * {"not":     조건}             부정
 * {"gte":     ["수치경로", 정수]}
 * {"gt":      ["수치경로", 정수]}
 * {"lte":     ["수치경로", 정수]}
 * {"lt":      ["수치경로", 정수]}
 * {"eq":      ["수치경로", 정수]}
 * {"has":     ["flags"|"inventory", "항목"]}
 * {"turnGte": 정수}
 * </pre>
 *
 * <p><b>{@code null} 조건은 받지 않는다.</b> 뜻이 호출자마다 다르기 때문이다 —
 * {@code chapter_def.entry_condition = NULL} 은 "진입 조건 없음"이고,
 * {@code ending_def.condition = NULL} 은 §13-16 상 <b>기본 엔딩이며 조건 판정에 참여하지 않는다.</b>
 * 평가기가 한쪽으로 정하면 다른 쪽이 조용히 틀린다.
 */
public class ConditionEvaluator {

	private static final Logger log = LoggerFactory.getLogger(ConditionEvaluator.class);

	private static final String FLAGS = "flags";

	private static final String INVENTORY = "inventory";

	/**
	 * 조건식을 평가한다.
	 *
	 * @param condition 조건식. {@code null} 을 허용하지 않는다 — 위 클래스 주석 참조
	 * @param state     평가 대상 상태. 이 메서드는 상태를 바꾸지 않는다
	 */
	public boolean evaluate(JsonNode condition, GameState state) {
		if (condition == null || condition.isNull() || condition.isMissingNode()) {
			throw new IllegalArgumentException(
					"condition must not be null — NULL 의 의미는 호출자가 정한다 (§13-16)");
		}
		if (state == null) {
			throw new IllegalArgumentException("state is required");
		}

		List<String> unresolved = new ArrayList<>();
		boolean result = evaluate(condition, state, unresolved);
		warnUnresolved(unresolved);
		return result;
	}

	private boolean evaluate(JsonNode node, GameState state, List<String> unresolved) {
		if (!node.isObject() || node.size() != 1) {
			// 연산자 하나짜리 객체가 아니면 해석할 방법이 없다. 추측하지 않는다.
			unresolved.add("<malformed>");
			return false;
		}

		String operator = node.propertyNames().iterator().next();
		JsonNode operand = node.get(operator);

		return switch (operator) {
			case "all" -> all(operand, state, unresolved);
			case "any" -> any(operand, state, unresolved);
			case "not" -> !evaluate(operand, state, unresolved);
			case "gte", "gt", "lte", "lt", "eq" -> compare(operator, operand, state, unresolved);
			case "has" -> has(operand, state, unresolved);
			case "turnGte" -> operand.isIntegralNumber() && state.turn() >= operand.asInt();
			default -> {
				unresolved.add(operator);
				yield false;
			}
		};
	}

	/** 빈 배열은 {@code true} 다 (표준 논리). "조건 없음"은 컬럼을 {@code NULL} 로 두어 표현한다. */
	private boolean all(JsonNode operand, GameState state, List<String> unresolved) {
		if (!operand.isArray()) {
			unresolved.add("all");
			return false;
		}
		for (JsonNode child : operand) {
			if (!evaluate(child, state, unresolved)) {
				return false;
			}
		}
		return true;
	}

	/** 빈 배열은 {@code false} 다 (표준 논리). */
	private boolean any(JsonNode operand, GameState state, List<String> unresolved) {
		if (!operand.isArray()) {
			unresolved.add("any");
			return false;
		}
		for (JsonNode child : operand) {
			if (evaluate(child, state, unresolved)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 수치 비교. 경로가 GameState 에 없으면 {@code false} 다.
	 *
	 * <p>없는 수치를 {@code 0} 으로 보지 않는다 — {@code min} 이 양수인 스키마에서 "아직 값이 없다"와
	 * "0 이다"가 다른 뜻이 되고, {@code lt} 계열이 조용히 참이 된다.
	 */
	private boolean compare(String operator, JsonNode operand, GameState state, List<String> unresolved) {
		if (!operand.isArray() || operand.size() != 2 || !operand.get(1).isIntegralNumber()) {
			unresolved.add(operator);
			return false;
		}

		String path = operand.get(0).asString();
		Integer actual = state.numerics().get(path);
		if (actual == null) {
			unresolved.add(path);
			return false;
		}

		int expected = operand.get(1).asInt();
		return switch (operator) {
			case "gte" -> actual >= expected;
			case "gt" -> actual > expected;
			case "lte" -> actual <= expected;
			case "lt" -> actual < expected;
			default -> actual == expected;
		};
	}

	/** 컬렉션 보유 검사. 컬렉션 이름은 {@code flags} 또는 {@code inventory} 다. */
	private boolean has(JsonNode operand, GameState state, List<String> unresolved) {
		if (!operand.isArray() || operand.size() != 2) {
			unresolved.add("has");
			return false;
		}

		String collection = operand.get(0).asString();
		String item = operand.get(1).asString();

		return switch (collection) {
			case FLAGS -> state.flags().contains(item);
			case INVENTORY -> state.inventory().contains(item);
			default -> {
				unresolved.add("has:" + collection);
				yield false;
			}
		};
	}

	/**
	 * 해석하지 못한 참조를 남긴다.
	 *
	 * <p>조용히 {@code false} 를 돌려주면 "챕터가 안 넘어간다"의 원인을 찾을 수 없다. 반대로
	 * <b>키 이름만</b> 남긴다 — 조건식 값이나 본문은 애플리케이션 로그에 남기지 않는다 (S-3).
	 */
	private static void warnUnresolved(List<String> unresolved) {
		if (!unresolved.isEmpty()) {
			log.warn("condition references could not be resolved (evaluated as false): {}", unresolved);
		}
	}
}

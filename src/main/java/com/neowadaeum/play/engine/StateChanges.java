package com.neowadaeum.play.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * AI 가 제안한 상태 변화 (§5.2 {@code stateChanges}).
 *
 * <p><b>제안이다. 최종 권한은 서버에 있다.</b> {@link GameStateEngine} 이 화이트리스트로 거르고
 * clamp 한 뒤에야 상태가 된다 (R4.1, R4.2).
 *
 * <p><b>{@code chapter} 와 {@code turn} 을 담을 자리가 없다 (I-9, R4.3).</b> AI 가 그 키를 보내오면
 * §13-9 의 "이 외 키는 무시"에 걸려 {@link #ignoredKeys()} 로 빠진다. 무시하는 코드를 쓰는 대신
 * 담을 곳을 만들지 않았다.
 *
 * <p>허용 연산자는 §13-9 가 정한 일곱 가지다.
 *
 * <pre>
 * &lt;numericPath&gt;: delta      예) "affinity.yuna": 2
 * flags.add:      []
 * flags.remove:   []
 * inventory.add:  []
 * inventory.remove: []
 * location:       "강의실"
 * timeOfDay:      "오후"
 * </pre>
 */
public record StateChanges(
		Map<String, Integer> numericDeltas,
		List<String> flagsAdded,
		List<String> flagsRemoved,
		List<String> itemsAdded,
		List<String> itemsRemoved,
		String location,
		String timeOfDay,
		List<String> ignoredKeys) {

	private static final String FLAGS_ADD = "flags.add";

	private static final String FLAGS_REMOVE = "flags.remove";

	private static final String INVENTORY_ADD = "inventory.add";

	private static final String INVENTORY_REMOVE = "inventory.remove";

	private static final String LOCATION = "location";

	private static final String TIME_OF_DAY = "timeOfDay";

	public StateChanges {
		numericDeltas = Map.copyOf(numericDeltas == null ? Map.of() : numericDeltas);
		flagsAdded = List.copyOf(flagsAdded == null ? List.of() : flagsAdded);
		flagsRemoved = List.copyOf(flagsRemoved == null ? List.of() : flagsRemoved);
		itemsAdded = List.copyOf(itemsAdded == null ? List.of() : itemsAdded);
		itemsRemoved = List.copyOf(itemsRemoved == null ? List.of() : itemsRemoved);
		ignoredKeys = List.copyOf(ignoredKeys == null ? List.of() : ignoredKeys);
	}

	public static StateChanges none() {
		return new StateChanges(Map.of(), List.of(), List.of(), List.of(), List.of(), null, null, List.of());
	}

	/**
	 * AI 응답의 {@code stateChanges} 객체를 읽는다.
	 *
	 * <p>알 수 없는 키는 예외를 던지지 않고 {@link #ignoredKeys()} 에 모은다. 한 키가 이상하다고
	 * 턴 전체를 실패시키면 AI 의 사소한 흔들림이 서비스 장애가 된다 — 무시하되 <b>무시했다는 사실은
	 * 남긴다.</b>
	 */
	public static StateChanges from(JsonNode node) {
		if (node == null || node.isNull() || node.isMissingNode()) {
			return none();
		}
		if (!node.isObject()) {
			throw new IllegalArgumentException("stateChanges must be a JSON object");
		}

		Map<String, Integer> deltas = new LinkedHashMap<>();
		List<String> flagsAdded = new ArrayList<>();
		List<String> flagsRemoved = new ArrayList<>();
		List<String> itemsAdded = new ArrayList<>();
		List<String> itemsRemoved = new ArrayList<>();
		List<String> ignored = new ArrayList<>();
		String[] scalars = new String[2];

		node.propertyStream().forEach(entry -> {
			String key = entry.getKey();
			JsonNode value = entry.getValue();

			switch (key) {
				case FLAGS_ADD -> collectStrings(value, flagsAdded, key, ignored);
				case FLAGS_REMOVE -> collectStrings(value, flagsRemoved, key, ignored);
				case INVENTORY_ADD -> collectStrings(value, itemsAdded, key, ignored);
				case INVENTORY_REMOVE -> collectStrings(value, itemsRemoved, key, ignored);
				case LOCATION -> scalars[0] = readText(value, key, ignored);
				case TIME_OF_DAY -> scalars[1] = readText(value, key, ignored);
				default -> {
					// §13-9 — 그 외 키는 수치 델타로만 해석한다. 정수가 아니면 무시한다.
					if (value.isIntegralNumber()) {
						deltas.put(key, value.asInt());
					}
					else {
						ignored.add(key);
					}
				}
			}
		});

		return new StateChanges(deltas, flagsAdded, flagsRemoved, itemsAdded, itemsRemoved, scalars[0], scalars[1],
				ignored);
	}

	private static void collectStrings(JsonNode value, List<String> target, String key, List<String> ignored) {
		if (!value.isArray()) {
			ignored.add(key);
			return;
		}
		value.forEach(item -> target.add(item.asString()));
	}

	private static String readText(JsonNode value, String key, List<String> ignored) {
		if (!value.isString()) {
			ignored.add(key);
			return null;
		}
		return value.asString();
	}
}

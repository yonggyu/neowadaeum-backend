package com.neowadaeum.play.engine;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * {@code story_version.state_schema} — GameState 화이트리스트 (R4.1).
 *
 * <p><b>여기 없는 키는 AI 가 반환해도 무시된다.</b> 걸러 내는 것이 아니라 애초에 병합 대상이
 * 아니다 — 그것이 "화이트리스트"의 의미다.
 *
 * <p>JSON 구조는 S-4 시드가 정한 것을 그대로 따른다. 원문 R4.1 은 "화이트리스트를 따른다"만
 * 규정하고 구조를 규정하지 않으므로 <b>새로 발명하지 않는다.</b>
 *
 * <pre>
 * {
 *   "affinity": { "yuna": { "min": 0, "max": 100, "maxDeltaPerTurn": 5 } },
 *   "flags":    ["met_yuna", "shared_lunch"],
 *   "inventory": ["letter"]          // 선언하지 않으면 인벤토리 조작이 전부 무시된다
 * }
 * </pre>
 *
 * <p>수치 그룹은 top-level 객체다. 경로는 {@code <그룹>.<이름>} 이며 {@code stateChanges} 의 키와
 * 정확히 같은 표기다 — 두 표기가 다르면 매번 변환이 필요하고 그 변환이 조용히 틀린다.
 */
public record StateSchema(Map<String, NumericSpec> numerics, Set<String> flags, Set<String> inventory) {

	/** 배열로 선언되는 화이트리스트 키. 나머지 top-level 객체는 수치 그룹으로 읽는다. */
	private static final String FLAGS = "flags";

	private static final String INVENTORY = "inventory";

	public StateSchema {
		numerics = Map.copyOf(numerics == null ? Map.of() : numerics);
		flags = Set.copyOf(flags == null ? Set.of() : flags);
		inventory = Set.copyOf(inventory == null ? Set.of() : inventory);
	}

	public static StateSchema from(JsonNode root) {
		if (root == null || !root.isObject()) {
			throw new IllegalArgumentException("state_schema must be a JSON object");
		}

		Map<String, NumericSpec> numerics = new LinkedHashMap<>();
		Set<String> flags = new LinkedHashSet<>();
		Set<String> inventory = new LinkedHashSet<>();

		root.propertyStream().forEach(entry -> {
			String key = entry.getKey();
			JsonNode value = entry.getValue();

			if (FLAGS.equals(key)) {
				value.forEach(item -> flags.add(item.asString()));
			}
			else if (INVENTORY.equals(key)) {
				value.forEach(item -> inventory.add(item.asString()));
			}
			else if (value.isObject()) {
				value.propertyStream().forEach(field ->
						numerics.put(key + "." + field.getKey(), NumericSpec.from(field.getValue())));
			}
		});

		return new StateSchema(numerics, flags, inventory);
	}

	public boolean allowsNumeric(String path) {
		return this.numerics.containsKey(path);
	}

	public boolean allowsFlag(String flag) {
		return this.flags.contains(flag);
	}

	public boolean allowsItem(String item) {
		return this.inventory.contains(item);
	}

	public NumericSpec numeric(String path) {
		return this.numerics.get(path);
	}

	/**
	 * 수치 필드의 범위와 턴당 변화 상한 (R4.2).
	 *
	 * @param min             하한
	 * @param max             상한
	 * @param maxDeltaPerTurn 한 턴에 움직일 수 있는 절대값. 선언하지 않으면 {@link #DEFAULT_MAX_DELTA}
	 */
	public record NumericSpec(int min, int max, int maxDeltaPerTurn) {

		/** R4.2 — 기본 델타 상한 ±5. 작품이 선언하지 않았을 때 쓰는 값이다. */
		public static final int DEFAULT_MAX_DELTA = 5;

		public NumericSpec {
			if (max < min) {
				throw new IllegalArgumentException("max must not be below min");
			}
			if (maxDeltaPerTurn < 0) {
				throw new IllegalArgumentException("maxDeltaPerTurn must not be negative");
			}
		}

		static NumericSpec from(JsonNode node) {
			int min = node.path("min").asInt(0);
			int max = node.path("max").asInt(Integer.MAX_VALUE);
			int maxDelta = node.path("maxDeltaPerTurn").asInt(DEFAULT_MAX_DELTA);
			return new NumericSpec(min, max, maxDelta);
		}
	}
}

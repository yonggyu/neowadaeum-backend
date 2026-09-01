package com.neowadaeum.play.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * 챕터·턴·장소·시간·수치·플래그·인벤토리의 구조화 상태 (§3.2, §4.1).
 *
 * <p><b>I-9 · R4.3 — {@code chapter} 와 {@code turn} 은 서버 전용이다.</b> 이 클래스에서 둘을 바꾸는
 * 수단은 {@link #advanceTo(int, int)} 하나뿐이고, {@link GameStateEngine} 은 그것을 호출하지 않는다.
 * AI 의 {@code stateChanges} 가 닿을 수 있는 경로에 아예 두지 않는 것이 요점이다 — 값을 무시하는
 * 구현은 다음 사람이 되살릴 수 있지만, 경로가 없으면 되살릴 수 없다.
 *
 * <p>불변이다. 모든 변경은 새 인스턴스를 만든다 — 스냅샷이 append-only(I-5)인 것과 같은 이유다.
 *
 * <p>수치는 {@code "affinity.yuna"} 처럼 <b>점 경로 하나로 평평하게</b> 들고 있다가 직렬화할 때만
 * 중첩시킨다. {@code stateChanges} 의 키 표기와 같아서 변환이 필요 없다.
 */
public record GameState(
		int chapter,
		int turn,
		String location,
		String timeOfDay,
		Map<String, Integer> numerics,
		Set<String> flags,
		List<String> inventory) {

	private static final String CHAPTER = "chapter";

	private static final String TURN = "turn";

	private static final String LOCATION = "location";

	private static final String TIME_OF_DAY = "timeOfDay";

	private static final String FLAGS = "flags";

	private static final String INVENTORY = "inventory";

	public GameState {
		if (chapter < 1) {
			throw new IllegalArgumentException("chapter starts at 1");
		}
		if (turn < 0) {
			throw new IllegalArgumentException("turn must not be negative");
		}
		numerics = Map.copyOf(numerics == null ? Map.of() : numerics);
		flags = Set.copyOf(flags == null ? Set.of() : flags);
		inventory = List.copyOf(inventory == null ? List.of() : inventory);
	}

	/**
	 * <b>서버 전용 경로 (I-9, R4.3).</b> 챕터 전환과 턴 증가는 서버가 GameState 로 판정한 결과이지
	 * AI 가 제안할 수 있는 값이 아니다. 호출자는 S-7(Chapter 엔진)과 S-9(오케스트레이터)다.
	 */
	public GameState advanceTo(int nextChapter, int nextTurn) {
		return new GameState(nextChapter, nextTurn, this.location, this.timeOfDay, this.numerics, this.flags,
				this.inventory);
	}

	GameState withMerged(String newLocation, String newTimeOfDay, Map<String, Integer> newNumerics,
			Set<String> newFlags, List<String> newInventory) {
		return new GameState(this.chapter, this.turn, newLocation, newTimeOfDay, newNumerics, newFlags,
				newInventory);
	}

	/** §4.1 의 JSON 표현으로 되돌린다. 수치는 점 경로를 다시 중첩시킨다. */
	public ObjectNode toJson() {
		ObjectNode root = JsonNodeFactory.instance.objectNode();
		root.put(CHAPTER, this.chapter);
		root.put(TURN, this.turn);
		root.put(LOCATION, this.location);
		root.put(TIME_OF_DAY, this.timeOfDay);

		this.numerics.forEach((path, value) -> {
			int dot = path.indexOf('.');
			if (dot < 0) {
				root.put(path, value);
				return;
			}
			root.withObjectProperty(path.substring(0, dot)).put(path.substring(dot + 1), value);
		});

		ArrayNode flagArray = root.putArray(FLAGS);
		this.flags.forEach(flagArray::add);
		ArrayNode inventoryArray = root.putArray(INVENTORY);
		this.inventory.forEach(inventoryArray::add);

		return root;
	}

	/**
	 * §4.1 JSON 을 읽는다.
	 *
	 * <p>{@code chapter} · {@code turn} 도 함께 읽는다 — <b>저장된 상태를 복원하는 것</b>이지 AI 입력을
	 * 받는 것이 아니다. AI 경로는 {@link StateChanges} 이며 그쪽에는 두 키가 아예 없다.
	 */
	public static GameState from(JsonNode root) {
		if (root == null || !root.isObject()) {
			throw new IllegalArgumentException("game state must be a JSON object");
		}

		Map<String, Integer> numerics = new LinkedHashMap<>();
		root.propertyStream().forEach(entry -> {
			JsonNode value = entry.getValue();
			if (value.isObject()) {
				value.propertyStream()
						.forEach(field -> numerics.put(entry.getKey() + "." + field.getKey(), field.getValue().asInt()));
			}
		});

		Set<String> flags = new LinkedHashSet<>();
		root.path(FLAGS).forEach(node -> flags.add(node.asString()));

		List<String> inventory = new ArrayList<>();
		root.path(INVENTORY).forEach(node -> inventory.add(node.asString()));

		return new GameState(root.path(CHAPTER).asInt(1), root.path(TURN).asInt(0),
				root.path(LOCATION).asString(null), root.path(TIME_OF_DAY).asString(null),
				numerics, flags, inventory);
	}

	/** 세션 시작 상태. 챕터 1 · 턴 0 이며 수치는 비어 있다 (§4.2 의 초기화는 S-9 범위다). */
	public static GameState initial() {
		return new GameState(1, 0, null, null, Map.of(), Set.of(), List.of());
	}
}

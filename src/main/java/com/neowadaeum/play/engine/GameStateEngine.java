package com.neowadaeum.play.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AI 의 제안을 서버의 상태로 바꾸는 엔진 (B-26 / S-5).
 *
 * <p><b>순서가 규칙이다 — 화이트리스트 필터 → clamp → 병합</b> (§4.3-8). 이 순서가 무너지면
 * AI 출력이 게임 상태의 최종 권한이 된다.
 *
 * <ul>
 *   <li><b>R4.1</b> {@code state_schema} 에 없는 키는 무시한다. 거르는 것이 아니라 병합 대상이 아니다.
 *   <li><b>R4.2</b> 델타를 {@code maxDeltaPerTurn}(기본 ±5)으로 자른 뒤, 결과를 {@code min}/{@code max}
 *       범위로 다시 자른다. <b>두 번 자르는 것이 요점이다</b> — 범위만 걸면 한 턴에 0 에서 100 으로
 *       뛰는 것을 막지 못하고, 델타만 걸면 5 씩 쌓여 상한을 넘는다.
 *   <li><b>I-9 · R4.3</b> {@code chapter} / {@code turn} 은 이 엔진이 건드리지 않는다.
 *       {@link StateChanges} 에 담을 자리조차 없다.
 *   <li><b>I-15 · R11.7</b> 난수가 없다. 같은 입력은 언제나 같은 출력이다.
 * </ul>
 *
 * <p>상태를 바꾸지 않고 <b>새 상태를 돌려준다.</b> 스냅샷이 append-only(I-5)이므로 이전 상태도
 * 그대로 살아 있어야 한다.
 */
public class GameStateEngine {

	private static final Logger log = LoggerFactory.getLogger(GameStateEngine.class);

	/**
	 * 제안을 적용한 새 상태를 만든다.
	 *
	 * @param current 현재 상태. 챕터·턴은 그대로 보존된다
	 * @param schema  이 작품 버전의 화이트리스트
	 * @param changes AI 가 제안한 변화
	 */
	public GameState apply(GameState current, StateSchema schema, StateChanges changes) {
		if (current == null || schema == null) {
			throw new IllegalArgumentException("current state and schema are required");
		}
		StateChanges proposal = (changes != null) ? changes : StateChanges.none();

		List<String> rejected = new ArrayList<>(proposal.ignoredKeys());

		Map<String, Integer> numerics = mergeNumerics(current, schema, proposal, rejected);
		Set<String> flags = mergeFlags(current, schema, proposal, rejected);
		List<String> inventory = mergeInventory(current, schema, proposal, rejected);

		warnRejected(rejected);

		return current.withMerged(
				(proposal.location() != null) ? proposal.location() : current.location(),
				(proposal.timeOfDay() != null) ? proposal.timeOfDay() : current.timeOfDay(),
				numerics, flags, inventory);
	}

	private Map<String, Integer> mergeNumerics(GameState current, StateSchema schema, StateChanges proposal,
			List<String> rejected) {
		Map<String, Integer> merged = new LinkedHashMap<>(current.numerics());

		proposal.numericDeltas().forEach((path, delta) -> {
			if (!schema.allowsNumeric(path)) {
				rejected.add(path);
				return;
			}
			StateSchema.NumericSpec spec = schema.numeric(path);
			int base = merged.getOrDefault(path, spec.min());
			merged.put(path, clamp(base, delta, spec));
		});

		return merged;
	}

	/** R4.2 — 델타 상한으로 한 번, 값 범위로 한 번. 순서를 바꾸면 상한 위반이 통과한다. */
	private static int clamp(int base, int delta, StateSchema.NumericSpec spec) {
		int cappedDelta = Math.clamp(delta, -spec.maxDeltaPerTurn(), spec.maxDeltaPerTurn());
		return Math.clamp((long) base + cappedDelta, spec.min(), spec.max());
	}

	private Set<String> mergeFlags(GameState current, StateSchema schema, StateChanges proposal,
			List<String> rejected) {
		Set<String> merged = new LinkedHashSet<>(current.flags());

		proposal.flagsAdded().forEach(flag -> {
			if (schema.allowsFlag(flag)) {
				merged.add(flag);
			}
			else {
				rejected.add("flags.add:" + flag);
			}
		});

		// 제거는 화이트리스트를 묻지 않는다. 이미 들어와 있는 값을 빼는 것은 상태를 넓히지 않는다.
		proposal.flagsRemoved().forEach(merged::remove);

		return merged;
	}

	private List<String> mergeInventory(GameState current, StateSchema schema, StateChanges proposal,
			List<String> rejected) {
		List<String> merged = new ArrayList<>(current.inventory());

		proposal.itemsAdded().forEach(item -> {
			if (schema.allowsItem(item)) {
				merged.add(item);
			}
			else {
				rejected.add("inventory.add:" + item);
			}
		});

		proposal.itemsRemoved().forEach(merged::remove);

		return merged;
	}

	/**
	 * 무시한 키를 남긴다.
	 *
	 * <p>조용히 버리면 프롬프트가 잘못된 키를 계속 만들어도 아무도 모른다. 반대로 <b>키 이름만</b>
	 * 남긴다 — 본문·응답 원문은 애플리케이션 로그에 남기지 않는다 (S-3).
	 */
	private static void warnRejected(List<String> rejected) {
		if (!rejected.isEmpty()) {
			log.warn("stateChanges keys ignored (not in state_schema or unknown operator): {}", rejected);
		}
	}
}

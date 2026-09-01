package com.neowadaeum.play.engine;

import tools.jackson.databind.JsonNode;

/**
 * 판정에 필요한 엔딩 정의 (§2.3 {@code ending_def} 의 부분집합).
 *
 * <p><b>§13-16 · R2.11</b> — {@code defaultEnding} 과 {@code condition} 은 배타다. 기본 엔딩은
 * 조건을 갖지 않고 조건 판정에 <b>참여하지 않는</b> fallback 이며, 일반 엔딩은 조건을 반드시 갖는다.
 * DB 가 이미 CHECK 로 강제하지만(`catalog/V4`) 여기서도 한 번 더 막는다 — 엔진에 잘못된 조합이
 * 들어오면 순회가 조용히 틀리기 때문이다.
 *
 * @param endingNo       1 부터 시작. <b>이 순서로 평가한다</b> (R7.6)
 * @param label          표시 라벨. 판정에는 쓰이지 않는다
 * @param condition      도달 조건. 기본 엔딩이면 {@code null}
 * @param secret         총계에서 제외되는 엔딩 (R7.11)
 * @param defaultEnding  어떤 조건에도 걸리지 않을 때의 폴백 (R2.2, R7.7)
 */
public record EndingDefinition(int endingNo, String label, JsonNode condition, boolean secret,
		boolean defaultEnding) {

	public EndingDefinition {
		if (endingNo < 1) {
			throw new IllegalArgumentException("endingNo starts at 1");
		}
		boolean hasCondition = condition != null && !condition.isNull() && !condition.isMissingNode();
		if (defaultEnding == hasCondition) {
			throw new IllegalArgumentException(
					"§13-16: default ending must not carry a condition, and a non-default ending must have one");
		}
		if (defaultEnding && secret) {
			throw new IllegalArgumentException("a default ending must not be secret (R7.11)");
		}
	}
}

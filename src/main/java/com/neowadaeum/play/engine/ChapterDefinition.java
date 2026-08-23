package com.neowadaeum.play.engine;

import tools.jackson.databind.JsonNode;

/**
 * 판정에 필요한 챕터 정의 (§2.3 {@code chapter_def} 의 부분집합).
 *
 * <p><b>catalog 엔티티가 아니다.</b> `play` 는 다른 모듈의 Entity 를 참조하지 않으며(§5.4),
 * 판정에 실제로 쓰는 값만 담은 DTO 로 받는다. 호출자(S-9)가 catalog 파사드에서 조립한다.
 *
 * @param chapterNo      1 부터 시작하는 챕터 번호
 * @param title          전환 응답에 담기는 제목 (R7.3). 판정에는 쓰이지 않는다
 * @param entryCondition 진입 조건. <b>{@code null} 이면 "조건 없음"이며 통과한다</b> —
 *                       {@code ending_def} 의 {@code null} 과 뜻이 다르다 (§13-16)
 * @param minTurns       이 챕터에 최소 머무는 턴 수 (R7.2)
 * @param maxTurns       이 턴 수에 도달하면 조건과 무관하게 강제 전환한다 (R7.2)
 */
public record ChapterDefinition(int chapterNo, String title, JsonNode entryCondition, int minTurns, int maxTurns) {

	public ChapterDefinition {
		if (chapterNo < 1) {
			throw new IllegalArgumentException("chapterNo starts at 1");
		}
		if (minTurns < 1 || maxTurns < minTurns) {
			throw new IllegalArgumentException("turn bounds must satisfy 1 <= minTurns <= maxTurns");
		}
	}
}

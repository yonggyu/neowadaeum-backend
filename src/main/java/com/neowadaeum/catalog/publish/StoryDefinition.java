package com.neowadaeum.catalog.publish;

import java.util.List;
import java.util.UUID;

/**
 * 발행할 작품 한 벌 (B-53, B-56).
 *
 * <p><b>작품 하나는 넷이 함께 있어야 성립한다</b> — 작품·버전·챕터·엔딩. 나눠서 넘기면
 * 그중 하나만 있는 작품이 생기고, 그것은 플레이하다 <b>중간에 멈추는</b> 작품이다.
 *
 * <p><b>{@code authorRef} 는 {@code player_ref} 다</b> — 비-Identity 스토어는 {@code user.id} 를
 * 저장하지 않는다.
 *
 * @param stateTemplateKey 작성자가 고른 상태 템플릿 (R4.4, §13-9). 자유 정의가 아니다
 */
public record StoryDefinition(UUID authorRef, String title, String shortDesc, String worldIntro,
		String worldPrompt, String stateTemplateKey, List<Chapter> chapters, List<Ending> endings) {

	public StoryDefinition {
		chapters = List.copyOf(chapters == null ? List.of() : chapters);
		endings = List.copyOf(endings == null ? List.of() : endings);
		if (title == null || title.isBlank()) {
			throw new IllegalArgumentException("title is required");
		}
		if (worldPrompt == null || worldPrompt.isBlank()) {
			throw new IllegalArgumentException("worldPrompt is required");
		}
		if (chapters.isEmpty()) {
			// 챕터가 없으면 첫 턴부터 갈 곳이 없다.
			throw new IllegalArgumentException("a story needs at least one chapter");
		}
		if (endings.stream().filter(Ending::isDefault).count() != 1) {
			// R2.2 — 정확히 하나다. 0개면 폴백이 없어 세션이 끝나지 못하고, 2개면 어느 쪽으로
			// 끝나는지가 행 순서에 달린다.
			throw new IllegalArgumentException("exactly one default ending is required (R2.2)");
		}
	}

	/**
	 * @param entryConditionJson 진입 조건 (R7.4). 1장은 없다
	 */
	public record Chapter(int chapterNo, String title, String summarySeed, String entryConditionJson,
			int minTurns, int maxTurns) {
	}

	/**
	 * @param conditionJson 도달 조건. <b>기본 엔딩은 갖지 않는다</b> (§13-16)
	 */
	public record Ending(int endingNo, String label, String epilogueText, String conditionJson,
			boolean isDefault, boolean isSecret) {
	}
}

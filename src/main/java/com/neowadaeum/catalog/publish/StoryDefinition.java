package com.neowadaeum.catalog.publish;

import java.util.List;
import java.util.UUID;

/**
 * 발행할 작품 한 벌 (B-53, B-56).
 *
 * <p><b>작품 하나는 다섯이 함께 있어야 성립한다</b> — 작품·버전·챕터·엔딩·인물. 나눠서 넘기면
 * 그중 하나만 있는 작품이 생기고, 그것은 플레이하다 <b>중간에 멈추는</b> 작품이다.
 *
 * <p><b>인물이 늦게 들어왔다</b> (#350). 그 전까지 UGC 작품은 인물 없이 발행됐고, 그래서
 * 페르소나 프롬프트가 <b>매 턴 어디에도 쓰이지 않았으며</b> 검수자가 보는 인물 목록도 늘
 * 비어 있었다 — 계약이 그 값을 요구하고 있는데도 그랬다.
 *
 * <p><b>{@code authorRef} 는 {@code player_ref} 다</b> — 비-Identity 스토어는 {@code user.id} 를
 * 저장하지 않는다.
 *
 * @param stateTemplateKey 작성자가 고른 상태 템플릿 (R4.4, §13-9). 자유 정의가 아니다
 * @param genreKeys 작성자가 고른 장르의 {@code key} 들. <b>정본은 {@code genre} 표</b>이므로
 *     여기 오는 것은 이름이 아니라 키다 (§13-56)
 * @param coverImageKey 커버 이미지의 <b>객체 키</b> (#315). URL 이 아니다 — 버킷이 비공개이며
 *     읽기 URL 은 서버가 그때그때 서명한다 (I-8)
 */
public record StoryDefinition(UUID authorRef, String title, String shortDesc, String worldIntro,
		String worldPrompt, String stateTemplateKey, List<Chapter> chapters, List<Ending> endings,
		List<Character> characters, List<String> genreKeys, String coverImageKey) {

	public StoryDefinition {
		genreKeys = List.copyOf(genreKeys == null ? List.of() : genreKeys);
		chapters = List.copyOf(chapters == null ? List.of() : chapters);
		endings = List.copyOf(endings == null ? List.of() : endings);
		characters = List.copyOf(characters == null ? List.of() : characters);
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

	/**
	 * 등장인물 하나 (#350).
	 *
	 * <p><b>{@code personaPrompt} 가 이 record 의 핵심이다.</b> 매 턴 모델에게 들어가는 문장이며
	 * ({@code PromptAssembler} 의 인물 레이어), 검수자가 판정하는 대상이기도 하다 — 상세 화면이
	 * 감추는 값을 검수는 반대로 펼친다.
	 *
	 * <p><b>{@code displayOrder} 는 버전 안에서 유일하다</b> (DB 제약). 작성자가 정한 순서이며
	 * 배열의 자리에서 나온다 — 화면이 순서를 바꿀 수 있으므로 그 순서가 곧 값이다.
	 *
	 * @param portraitUrl 초상 <b>객체 키</b> (#315). 없을 수 있다 — 버킷이 비공개이므로 이 값이
	 *     있다고 이미지가 보이는 것은 아니다 (I-8)
	 * @param visibleInDetail 작품 상세의 인물 카드에 뜨는가 (R7.11 의 숨은 인물과 같은 축)
	 */
	public record Character(int displayOrder, String name, String oneLine, String personaPrompt,
			String portraitUrl, boolean visibleInDetail) {
	}
}

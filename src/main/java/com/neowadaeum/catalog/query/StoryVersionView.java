package com.neowadaeum.catalog.query;

import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * 턴 파이프라인이 읽는 작품 버전 한 벌 (S-9-1).
 *
 * <p><b>세션은 이 버전에 고정된다</b> (I-4, R2.1). 진행 중 세션은 새 버전이 발행돼도 영향받지
 * 않으므로, 파이프라인은 세션이 들고 있는 {@code storyVersionId} 로만 조회한다.
 *
 * <p>챕터·엔딩이 같이 오는 이유는 <b>한 턴에 셋 다 필요하기 때문</b>이다 (§4.3 의 8·9·10 단계).
 * 나눠 부르면 같은 버전을 세 번 읽는다.
 *
 * <p><b>{@code worldPrompt} 와 {@code characters} 는 B-22 에서 늘었다.</b> 판정에는 쓰이지 않고
 * <b>프롬프트의 작품 레이어</b>가 된다 (§5.1 의 WORLD · CHARACTER). 판정 재료와 같은 조회로
 * 가져오는 이유는 위와 같다 — 한 턴에 둘 다 필요하고, 나눠 부르면 같은 버전을 두 번 읽는다.
 *
 * @param storyVersionId 버전 식별자
 * @param worldPrompt    작품 세계관. <b>UGC 하드 제한 1,000토큰의 절반을 차지한다</b> (R4.9)
 * @param stateSchema    GameState 화이트리스트 (R4.1)
 * @param choicePolicy   선택지 개수 정책 (§2.3)
 * @param characters     등장인물. <b>{@code display_order} 순이다</b> — 프롬프트에 그 순서로 들어간다 (§4.4)
 * @param chapters       챕터 정의 전부
 * @param endings        엔딩 정의 전부
 */
public record StoryVersionView(
		UUID storyVersionId,
		String worldPrompt,
		JsonNode stateSchema,
		JsonNode choicePolicy,
		List<CharacterView> characters,
		List<ChapterView> chapters,
		List<EndingView> endings) {

	public StoryVersionView {
		characters = List.copyOf(characters == null ? List.of() : characters);
		chapters = List.copyOf(chapters == null ? List.of() : chapters);
		endings = List.copyOf(endings == null ? List.of() : endings);
	}

	/**
	 * {@code character} 중 <b>프롬프트에 들어가는 것만</b>.
	 *
	 * <p>{@code portrait_url} · {@code one_line} · {@code is_visible_in_detail} 은 작품 상세
	 * 화면의 것이지 프롬프트의 것이 아니다 (B-16). 여기에 담으면 매 턴 예산을 먹는다.
	 *
	 * @param name    작중 이름. 회원 정보가 아니라 작품 데이터다
	 * @param persona {@code character.persona_prompt}
	 */
	public record CharacterView(String name, String persona) {
	}

	/** {@code chapter_def} 중 판정에 쓰는 것만. */
	public record ChapterView(int chapterNo, String title, JsonNode entryCondition, int minTurns, int maxTurns) {
	}

	/**
	 * {@code ending_def} 중 판정에 쓰는 것과 <b>식별자</b>.
	 *
	 * <p>{@code id} 를 함께 싣는다. 없으면 호출자가 {@code (버전, 엔딩 번호)} 로 값을 만들어 내야
	 * 하고, 그렇게 만든 값은 <b>실제 행을 가리키지 않는다</b> — {@code turn.ending_id} ·
	 * {@code play_session.current_ending_id} 에 저장되는 값이므로 조회가 성립해야 한다.
	 */
	public record EndingView(UUID id, int endingNo, String label, JsonNode condition, boolean secret,
			boolean defaultEnding) {
	}
}

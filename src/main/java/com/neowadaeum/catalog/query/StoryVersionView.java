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
 * @param storyVersionId 버전 식별자
 * @param stateSchema    GameState 화이트리스트 (R4.1)
 * @param choicePolicy   선택지 개수 정책 (§2.3)
 * @param chapters       챕터 정의 전부
 * @param endings        엔딩 정의 전부
 */
public record StoryVersionView(
		UUID storyVersionId,
		JsonNode stateSchema,
		JsonNode choicePolicy,
		List<ChapterView> chapters,
		List<EndingView> endings) {

	public StoryVersionView {
		chapters = List.copyOf(chapters == null ? List.of() : chapters);
		endings = List.copyOf(endings == null ? List.of() : endings);
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

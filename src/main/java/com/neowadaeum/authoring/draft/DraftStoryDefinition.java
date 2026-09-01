package com.neowadaeum.authoring.draft;

import com.neowadaeum.catalog.publish.StoryDefinition;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 원고를 <b>발행할 수 있는 한 벌</b>로 옮긴다 (B-53, B-56).
 *
 * <p><b>여기가 원고 {@code payload} 를 해석하는 유일한 자리다.</b> B-51 이 payload 를 통째로
 * 저장하기로 한 것은 단계가 늘 때마다 컬럼이 느는 것을 막기 위해서였고, 그 대가는 <b>언젠가
 * 누군가는 그것을 읽어야 한다</b>는 것이다 — 그 자리를 하나로 둔다.
 *
 * <p><b>모자란 것은 채우지 않고 거절한다.</b> 빠진 챕터를 지어내면 작성자는 자기가 쓰지 않은
 * 작품을 미리 보게 된다.
 */
public final class DraftStoryDefinition {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** 미리보기는 3턴이다 (R8.13) — 챕터의 상한이 그보다 낮으면 3턴을 채우지 못한다. */
	private static final int PREVIEW_CHAPTER_MAX_TURNS = 10;

	private static final int PREVIEW_CHAPTER_MIN_TURNS = 1;

	/**
	 * 조건이 아직 없는 엔딩에 붙이는 조건 (§13-16).
	 *
	 * <p><b>{@code turnGte} 를 파이프라인이 절대 닿지 않는 값으로 둔다.</b> 조건 없이 넣으면
	 * DB 가 거절하고(일반 엔딩은 조건을 반드시 갖는다), 거절을 피하려 기본으로 바꾸면 그
	 * 엔딩이 폴백이 되어 <b>첫 턴부터 끝나 버린다.</b>
	 */
	private static final String UNREACHABLE_CONDITION = "{\"turnGte\":1000000}";

	private DraftStoryDefinition() {
	}

	/**
	 * @throws ApiException {@code VALIDATION_ERROR} — 발행에 필요한 것이 빠졌다. 어느 단계가
	 *     남았는지는 작성자가 화면에서 안다
	 */
	public static StoryDefinition from(UUID authorRef, String payload) {
		JsonNode root = JSON.readTree(payload);
		String title = text(root, "title");
		String worldPrompt = text(root, "worldPrompt");

		List<StoryDefinition.Chapter> chapters = new ArrayList<>();
		int chapterNo = 1;
		for (JsonNode chapter : root.path("chapters")) {
			chapters.add(new StoryDefinition.Chapter(chapterNo++, text(chapter, "title"),
					chapter.path("summarySeed").asString(null), null, PREVIEW_CHAPTER_MIN_TURNS,
					PREVIEW_CHAPTER_MAX_TURNS));
		}
		if (chapters.isEmpty()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}

		return new StoryDefinition(authorRef, title, root.path("shortDesc").asString(null),
				root.path("worldIntro").asString(null), worldPrompt, "affinity", chapters,
				endingsOf(root));
	}

	/**
	 * <b>기본 엔딩을 서버가 정한다</b> (§13-16, I-10).
	 *
	 * <p>작성자의 엔딩 목록에 기본이 없으면 세션이 끝나지 못한다 (R7.7). 마지막 항목을 기본으로
	 * 삼는 대신 <b>따로 하나를 더한다</b> — 작성자가 쓴 엔딩을 조건 없는 폴백으로 바꿔 버리면
	 * 그 엔딩은 <b>아무 조건에서나</b> 나온다.
	 *
	 * <p><b>일반 엔딩은 조건을 반드시 갖는다</b> (V4 의 CHECK, §13-16). 조건은 템플릿에서
	 * 고르는 값인데(R7.16) 미리보기 단계에서는 아직 고르지 않았다 — 그래서 <b>도달할 수 없는
	 * 조건</b>을 붙인다. 조건 없이 넣으면 그 엔딩이 폴백이 되어 <b>첫 턴부터 끝나 버린다.</b>
	 *
	 * <p>미리보기에서 그 엔딩들에 닿지 못하는 것은 <b>맞다</b> — 조건이 정해지기 전이므로
	 * 닿는 것이 오히려 거짓말이다. 작성자가 확인하는 것은 도입부의 문체와 흐름이다.
	 */
	private static List<StoryDefinition.Ending> endingsOf(JsonNode root) {
		List<StoryDefinition.Ending> endings = new ArrayList<>();
		int endingNo = 1;
		for (JsonNode ending : root.path("endings")) {
			endings.add(new StoryDefinition.Ending(endingNo++, text(ending, "label"),
					ending.path("epilogueText").asString(null), UNREACHABLE_CONDITION, false, false));
		}
		endings.add(new StoryDefinition.Ending(endingNo, "이야기의 끝", null, null, true, false));
		return endings;
	}

	private static String text(JsonNode node, String field) {
		String value = node.path(field).asString(null);
		if (value == null || value.isBlank()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
		return value;
	}
}

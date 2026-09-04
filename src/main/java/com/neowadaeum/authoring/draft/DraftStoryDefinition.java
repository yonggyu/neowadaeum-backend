package com.neowadaeum.authoring.draft;

import com.neowadaeum.authoring.outline.ConditionAssembler;
import com.neowadaeum.authoring.outline.ConditionSelection;
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
 * <p><b>상태 스키마도 함께 나온다</b> (#326). 조건에 쓰는 이름과 화이트리스트에 선언되는 이름은
 * <b>같은 목록</b>이어야 하며, 두 자리에서 따로 읽으면 <b>검증을 통과한 조건이 런타임에
 * 거짓</b>이 된다 ({@link DraftStateSchema}).
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
	 * 발행에 필요한 한 벌 — 작품 정의와 그 작품의 상태 화이트리스트.
	 *
	 * @param stateSchema {@code story_version.state_schema} 에 저장된다 (R4.1). <b>조건이 보는
	 *     이름과 같은 목록에서 나온다</b> (#326)
	 */
	public record Publishable(StoryDefinition definition, String stateSchema) {
	}

	/**
	 * @throws ApiException {@code VALIDATION_ERROR} — 발행에 필요한 것이 빠졌거나, 조건이
	 *     원고에 선언되지 않은 이름을 가리킨다. 어느 단계가 남았는지는 작성자가 화면에서 안다
	 */
	public static Publishable from(UUID authorRef, String payload) {
		JsonNode root = parse(payload);
		String title = text(root, "title");
		String worldPrompt = text(root, "worldPrompt");
		DraftStateSchema schema = DraftStateSchema.from(root);

		List<StoryDefinition.Chapter> chapters = new ArrayList<>();
		int chapterNo = 1;
		for (JsonNode chapter : root.path("chapters")) {
			chapters.add(new StoryDefinition.Chapter(chapterNo++, text(chapter, "title"),
					chapter.path("summarySeed").asString(null), conditionOf(chapter, schema),
					PREVIEW_CHAPTER_MIN_TURNS, PREVIEW_CHAPTER_MAX_TURNS));
		}
		if (chapters.isEmpty()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}

		StoryDefinition definition = new StoryDefinition(authorRef, title,
				root.path("shortDesc").asString(null), root.path("worldIntro").asString(null),
				worldPrompt, "affinity", chapters, endingsOf(root, schema));
		return new Publishable(definition, schema.toJson());
	}

	/**
	 * 저장 시점에 <b>조건만</b> 본다 (#326).
	 *
	 * <p><b>발행까지 미루지 않는 이유.</b> 없는 인물을 가리키는 조건은 평가기에서 조용히
	 * 거짓이 되고 그 챕터·엔딩은 영원히 도달되지 않는다 — 제출한 뒤에야 알려 주면 작성자는
	 * <b>왜 그 엔딩이 안 나오는지</b>를 끝내 알지 못한다.
	 *
	 * <p><b>여기서 나머지를 보지 않는다.</b> 아직 채우지 않은 단계가 있는 것은 정상이며
	 * (5단계 저장, R8.3), 제목이 없다고 저장을 막으면 <b>작성 중인 원고를 저장할 수 없다.</b>
	 */
	public static void validateConditions(String payload) {
		JsonNode root = parse(payload);
		DraftStateSchema schema = DraftStateSchema.from(root);
		root.path("chapters").forEach(chapter -> conditionOf(chapter, schema));
		root.path("endings").forEach(ending -> conditionOf(ending, schema));
	}

	/**
	 * <b>조건은 템플릿에서 고른 것이다</b> (R7.16, #326). 조립은 서버가 한다 — 작성자가 보낸
	 * 구조를 그대로 평가기에 먹이면 그것이 곧 DSL 입력면이 된다 (I-1 과 같은 이유).
	 *
	 * <p><b>고르지 않았으면 {@code null} 이다.</b> 챕터는 조건 없이 이어질 수 있다.
	 */
	private static String conditionOf(JsonNode node, DraftStateSchema schema) {
		return ConditionSelection.read(node.path("condition"))
				.map(selection -> ConditionAssembler.assemble(selection, schema.characters(),
						schema.flags()))
				.orElse(null);
	}

	/**
	 * <b>기본 엔딩을 서버가 정한다</b> (§13-16, I-10).
	 *
	 * <p>작성자의 엔딩 목록에 기본이 없으면 세션이 끝나지 못한다 (R7.7). 마지막 항목을 기본으로
	 * 삼는 대신 <b>따로 하나를 더한다</b> — 작성자가 쓴 엔딩을 조건 없는 폴백으로 바꿔 버리면
	 * 그 엔딩은 <b>아무 조건에서나</b> 나온다.
	 *
	 * <p><b>일반 엔딩은 조건을 반드시 갖는다</b> (V4 의 CHECK, §13-16). 작성자가 고른 조건이
	 * 있으면 그것을 쓰고, 아직 고르지 않았으면 <b>도달할 수 없는 조건</b>을 붙인다 (#326 이전에는
	 * 늘 후자였다). 조건 없이 넣으면 그 엔딩이 폴백이 되어 <b>첫 턴부터 끝나 버린다.</b>
	 *
	 * <p>조건을 고르지 않은 엔딩에 미리보기가 닿지 못하는 것은 <b>맞다</b> — 닿는 것이 오히려
	 * 거짓말이다. 작성자가 확인하는 것은 도입부의 문체와 흐름이다.
	 */
	private static List<StoryDefinition.Ending> endingsOf(JsonNode root, DraftStateSchema schema) {
		List<StoryDefinition.Ending> endings = new ArrayList<>();
		int endingNo = 1;
		for (JsonNode ending : root.path("endings")) {
			String condition = conditionOf(ending, schema);
			endings.add(new StoryDefinition.Ending(endingNo++, text(ending, "label"),
					ending.path("epilogueText").asString(null),
					(condition != null) ? condition : UNREACHABLE_CONDITION, false, false));
		}
		endings.add(new StoryDefinition.Ending(endingNo, "이야기의 끝", null, null, true, false));
		return endings;
	}

	/**
	 * <b>읽을 수 없는 payload 는 400 이다.</b> 그대로 던지면 500 이 되고, 그러면 작성자는
	 * <b>서버가 고장 났다</b>고 읽는다 — 보낸 것이 JSON 이 아닌 것은 요청의 문제다.
	 */
	private static JsonNode parse(String payload) {
		try {
			JsonNode root = JSON.readTree(payload);
			if (!root.isObject()) {
				throw new ApiException(ErrorCode.VALIDATION_ERROR);
			}
			return root;
		}
		catch (RuntimeException ex) {
			if (ex instanceof ApiException apiException) {
				throw apiException;
			}
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
	}

	private static String text(JsonNode node, String field) {
		String value = node.path(field).asString(null);
		if (value == null || value.isBlank()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
		return value;
	}
}

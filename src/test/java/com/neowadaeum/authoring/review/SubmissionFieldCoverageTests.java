package com.neowadaeum.authoring.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.authoring.draft.DraftStateSchema;
import com.neowadaeum.catalog.publish.StoryDefinition;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * R8.5 — <b>손으로 적은 목록이 조용히 짧아지지 못하게 한다</b> (§13-75, #366).
 *
 * <p>인물과 플래그가 L1 을 지나지 않은 것은 코드가 틀려서가 아니라 <b>작성자가 쓰는 값이
 * 늘었는데 목록은 그대로였기 때문</b>이다 (#350 · #362). 조용히 빠진 값은 예외를 내지 않으므로
 * 아무도 알아채지 못하고, 같은 일이 이번까지 두 번이었다.
 *
 * <p><b>파생시키는 대신 세는 쪽을 둔다.</b> 프롬프트 레이어에서 목록을 뽑으면 검수가 레이어보다
 * <b>좁아진다</b> — 소개글은 모델에게 가지 않지만 타인에게 보인다 (I-8). 그래서 목록은 여전히
 * 손으로 적되, 정의에 텍스트 성분이 하나 늘면 <b>여기서 실패한다</b>: 그때 사람이 *이것이 검수
 * 대상인가* 를 정하고 둘 중 한 목록에 적는다.
 *
 * <p><b>S-11 — 픽스처는 전부 가상의 표식 문자열이다.</b> 블록리스트를 쓰지 않는다 — 여기서
 * 확인하는 것은 판정이 아니라 <b>무엇이 판정기에 닿는가</b> 다.
 */
class SubmissionFieldCoverageTests {

	/** 표식은 값마다 다르다 — 어느 성분이 새는지 실패 메시지가 스스로 말해야 한다. */
	private static final String MARK = "표식";

	/** L1 이 거는 것. <b>{@link #R8_5_every_screened_text_reaches_the_screen()} 이 값으로 확인한다.</b> */
	private static final List<String> SCREENED = List.of("title", "shortDesc", "worldIntro",
			"worldPrompt", "chapters[].title", "chapters[].summarySeed", "endings[].label",
			"endings[].epilogueText", "characters[].name", "characters[].oneLine",
			"characters[].personaPrompt");

	/**
	 * 작성자가 쓴 글이 아닌 성분과 <b>그 이유</b>.
	 *
	 * <p>이유를 함께 적는 이유는, 목록만 있으면 다음 사람이 <b>거슬리는 실패를 지우는 자리</b>로
	 * 쓰기 때문이다.
	 */
	private static final Map<String, String> NOT_AUTHOR_TEXT = Map.of(
			"stateTemplateKey", "플랫폼 템플릿 키다 (R4.4, §13-9). 작성자가 고르는 것은 목록 중 하나다",
			"genreKeys", "장르 표의 키다 (§13-56). 정본이 표이므로 자유 입력이 아니다",
			"coverImageKey", "업로드가 확정한 객체 키다 (#315). 작성자가 쓴 문장이 아니다",
			"chapters[].entryConditionJson", "서버가 템플릿에서 조립한다 (R7.16, §13-69)",
			"endings[].conditionJson", "같은 이유다 — 작성자가 보낸 것은 고른 것뿐이다",
			"characters[].portraitUrl", "업로드가 확정한 객체 키다 (#315)");

	/**
	 * <b>새 값은 둘 중 한 목록에 적혀야 한다.</b>
	 *
	 * <p>이 실패는 버그가 아니라 <b>결정이 남았다</b>는 뜻이다 — 인물이 정의에 붙던 날(#350) 이
	 * 테스트가 있었다면 그날 실패했다.
	 */
	@Test
	void R8_5_a_new_text_of_the_definition_must_be_decided_here() {
		assertThat(textComponentsOf(StoryDefinition.class, ""))
				.as("정의의 텍스트 성분은 L1 이 걸거나(SCREENED) 이유와 함께 제외하거나(NOT_AUTHOR_TEXT) "
						+ "둘 중 하나다 (R8.5, §13-75)")
				.containsExactlyInAnyOrderElementsOf(union(SCREENED, NOT_AUTHOR_TEXT.keySet()));
	}

	/** <b>목록에 적힌 것이 실제로 판정기까지 간다.</b> 적어 두고 걸지 않으면 목록이 거짓이 된다. */
	@Test
	void R8_5_every_screened_text_reaches_the_screen() {
		Collection<String> screened = SubmissionService.fieldsOf(definitionOfMarks(), Set.of())
				.values();

		assertThat(SCREENED).allSatisfy(component -> assertThat(screened)
				.as("%s 가 L1 에 닿지 않는다", component).contains(mark(component)));
	}

	/** <b>제외한 것은 가지 않는다.</b> 조립된 조건 JSON 과 객체 키까지 걸면 반려 사유가 거짓이 된다. */
	@Test
	void R8_5_what_the_author_did_not_write_is_not_screened() {
		Collection<String> screened = SubmissionService.fieldsOf(definitionOfMarks(), Set.of())
				.values();

		assertThat(NOT_AUTHOR_TEXT.keySet()).allSatisfy(component -> assertThat(screened)
				.as("%s 는 %s", component, NOT_AUTHOR_TEXT.get(component))
				.doesNotContain(mark(component)));
	}

	/**
	 * <b>원고가 선언한 이름의 목록이 늘면 여기서 실패한다</b> (#362, #366).
	 *
	 * <p>플래그는 정의에 없다 — 화이트리스트로 발행될 뿐이므로 위의 성분 세기가 닿지 못한다.
	 * 인물 이름은 {@code characters[].name} 으로, 플래그는 {@code flags[]} 로 걸린다.
	 */
	@Test
	void R8_5_a_new_declared_name_list_must_be_decided_here() {
		assertThat(textComponentsOf(DraftStateSchema.class, ""))
				.as("원고가 선언하는 이름이 늘면 L1 이 그것을 볼지 정해야 한다 (R8.5, §13-75)")
				.containsExactlyInAnyOrder("characters", "flags");
	}

	/**
	 * <b>플래그 이름도 걸린다</b> (§13-75).
	 *
	 * <p>짧다는 이유로 다르게 보지 않는다 — 판정이 둘이 되면 무른 쪽이 곧 길이 된다.
	 */
	@Test
	void R8_5_declared_flag_names_reach_the_screen() {
		Map<String, String> fields = SubmissionService.fieldsOf(definitionOfMarks(),
				new java.util.LinkedHashSet<>(List.of(mark("flags[0]"), mark("flags[1]"))));

		assertThat(fields).containsEntry("flags[0]", mark("flags[0]"))
				.containsEntry("flags[1]", mark("flags[1]"));
	}

	/**
	 * <b>경로는 화면이 쓰는 표기다</b> ({@code characters[0].name}).
	 *
	 * <p>계약이 그 표기를 정했고 {@code precheck} 도 그것으로 답한다 — 검수가 다른 표기를 쓰면
	 * 밑줄을 그을 자리를 화면이 찾지 못한다.
	 */
	@Test
	void R8_5_character_paths_use_the_notation_the_contract_declares() {
		Map<String, String> fields = SubmissionService.fieldsOf(definitionOfMarks(), Set.of());

		assertThat(fields).containsKeys("characters[0].name", "characters[0].oneLine",
				"characters[0].persona");
	}

	/**
	 * <b>대신 발행된 한 줄 소개를 두 번 걸지 않는다</b> (§13-71).
	 *
	 * <p>페르소나가 비어 있으면 한 줄 소개가 그 자리로 발행된다. 같은 문장을 두 자리에 걸면
	 * 작성자는 <b>비어 있는 칸</b>에 밑줄을 보게 되고, 무엇을 고쳐야 하는지 알 수 없다.
	 */
	@Test
	void R8_5_a_persona_that_fell_back_to_the_one_line_is_not_screened_twice() {
		StoryDefinition definition = definitionWith(new StoryDefinition.Character(1,
				mark("characters[].name"), mark("shared"), mark("shared"), null, true));

		assertThat(SubmissionService.fieldsOf(definition, Set.of()))
				.containsEntry("characters[0].oneLine", mark("shared"))
				.doesNotContainKey("characters[0].persona");
	}

	// ── 픽스처 ──────────────────────────────────────────────

	private static String mark(String component) {
		return MARK + "-" + component;
	}

	/** 성분마다 다른 표식을 넣은 한 벌. <b>어느 값이 어디서 왔는지</b>가 값 자체에 적혀 있다. */
	private static StoryDefinition definitionOfMarks() {
		return definitionWith(new StoryDefinition.Character(1, mark("characters[].name"),
				mark("characters[].oneLine"), mark("characters[].personaPrompt"),
				mark("characters[].portraitUrl"), true));
	}

	private static StoryDefinition definitionWith(StoryDefinition.Character character) {
		return new StoryDefinition(UUID.randomUUID(), mark("title"), mark("shortDesc"),
				mark("worldIntro"), mark("worldPrompt"), mark("stateTemplateKey"),
				List.of(new StoryDefinition.Chapter(1, mark("chapters[].title"),
						mark("chapters[].summarySeed"), mark("chapters[].entryConditionJson"), 1, 10)),
				List.of(
						new StoryDefinition.Ending(1, mark("endings[].label"),
								mark("endings[].epilogueText"), mark("endings[].conditionJson"), false, false),
						// R2.2 — 기본 엔딩은 정확히 하나다. 표식을 넣지 않는 것은 서버가 더하는
						// 자리이기 때문이다 (§13-16).
						new StoryDefinition.Ending(2, "끝", null, null, true, false)),
				List.of(character), List.of(mark("genreKeys")), mark("coverImageKey"));
	}

	// ── 성분 세기 ───────────────────────────────────────────

	/**
	 * record 의 <b>텍스트 성분</b> 경로를 모은다.
	 *
	 * <p>세는 것은 문자열과 문자열 목록, 그리고 중첩된 record 다 — 번호·플래그·식별자는 작성자가
	 * 쓰는 값이 아니므로 세지 않는다. 목록은 자리마다 값이 다르지 않으므로 {@code [].} 로 접는다.
	 */
	private static List<String> textComponentsOf(Class<?> type, String prefix) {
		List<String> components = new ArrayList<>();
		for (RecordComponent component : type.getRecordComponents()) {
			String path = prefix + component.getName();
			if (component.getType() == String.class) {
				components.add(path);
				continue;
			}
			Class<?> element = elementTypeOf(component);
			if (element == String.class) {
				components.add(path);
			}
			else if (element != null && element.isRecord()) {
				components.addAll(textComponentsOf(element, path + "[]."));
			}
		}
		return components;
	}

	/** {@code List<X>} · {@code Set<X>} 의 {@code X}. 그 밖은 {@code null} 이다. */
	private static Class<?> elementTypeOf(RecordComponent component) {
		if (!Collection.class.isAssignableFrom(component.getType())) {
			return null;
		}
		if (component.getGenericType() instanceof ParameterizedType parameterized
				&& parameterized.getActualTypeArguments()[0] instanceof Class<?> element) {
			return element;
		}
		return null;
	}

	private static List<String> union(List<String> left, Set<String> right) {
		List<String> all = new ArrayList<>(left);
		all.addAll(right);
		return all;
	}
}

package com.neowadaeum.authoring.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.catalog.publish.StoryDefinition;
import com.neowadaeum.common.error.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * B-53 — 원고를 <b>발행할 수 있는 한 벌</b>로 옮긴다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class DraftStoryDefinitionTests {

	private static final String PAYLOAD = """
			{"title":"봄의 학교","shortDesc":"짧은 소개","worldIntro":"소개",
			 "worldPrompt":"봄의 학교에서 시작한다.",
			 "chapters":[{"title":"1장","summarySeed":"시작"},{"title":"2장","summarySeed":"전개"}],
			 "endings":[{"label":"좋은 끝","epilogueText":"잘 끝났다."}]}
			""";

	/** 번호는 서버가 붙인다 — 원고에 없는 값이다. */
	@Test
	void B53_chapters_are_numbered_by_the_server() {
		StoryDefinition definition = DraftStoryDefinition.from(UUID.randomUUID(), PAYLOAD);

		assertThat(definition.chapters()).extracting(StoryDefinition.Chapter::chapterNo)
				.containsExactly(1, 2);
	}

	/**
	 * <b>기본 엔딩을 따로 더한다</b> (§13-37, §13-16).
	 *
	 * <p>작성자가 쓴 엔딩을 조건 없는 폴백으로 바꿔 버리면 그 엔딩은 <b>아무 조건에서나</b>
	 * 나오고, 작성자는 자기가 쓰지 않은 분기를 보게 된다.
	 */
	@Test
	void S13_37_a_default_ending_is_added_rather_than_repurposed() {
		StoryDefinition definition = DraftStoryDefinition.from(UUID.randomUUID(), PAYLOAD);

		assertThat(definition.endings()).hasSize(2);
		assertThat(definition.endings().get(0).label()).isEqualTo("좋은 끝");
		assertThat(definition.endings().get(0).isDefault()).isFalse();
		assertThat(definition.endings().get(1).isDefault()).isTrue();
	}

	/** 엔딩을 하나도 쓰지 않아도 미리보기는 끝난다 — 기본 엔딩이 폴백이다 (R7.7). */
	@Test
	void R7_7_a_draft_without_endings_still_terminates() {
		String payload = PAYLOAD.replace(
				"\"endings\":[{\"label\":\"좋은 끝\",\"epilogueText\":\"잘 끝났다.\"}]", "\"endings\":[]");

		assertThat(DraftStoryDefinition.from(UUID.randomUUID(), payload).endings())
				.singleElement().extracting(StoryDefinition.Ending::isDefault).isEqualTo(true);
	}

	/** <b>모자란 것은 채우지 않고 거절한다</b> — 지어내면 쓰지 않은 작품을 미리 보게 된다. */
	@Test
	void B53_a_draft_without_a_world_prompt_is_refused() {
		String payload = PAYLOAD.replace("\"worldPrompt\":\"봄의 학교에서 시작한다.\"",
				"\"worldPrompt\":\"\"");

		assertThatThrownBy(() -> DraftStoryDefinition.from(UUID.randomUUID(), payload))
				.isInstanceOf(ApiException.class);
	}

	/** 챕터가 없으면 첫 턴부터 갈 곳이 없다. */
	@Test
	void B53_a_draft_without_chapters_is_refused() {
		String payload = PAYLOAD.replace(
				"\"chapters\":[{\"title\":\"1장\",\"summarySeed\":\"시작\"},{\"title\":\"2장\",\"summarySeed\":\"전개\"}]",
				"\"chapters\":[]");

		assertThatThrownBy(() -> DraftStoryDefinition.from(UUID.randomUUID(), payload))
				.isInstanceOf(ApiException.class);
	}

	/** 제목이 없으면 목록에 무엇으로 보일지 정해지지 않는다. */
	@Test
	void B53_a_draft_without_a_title_is_refused() {
		String payload = PAYLOAD.replace("\"title\":\"봄의 학교\"", "\"title\":\"  \"");

		assertThatThrownBy(() -> DraftStoryDefinition.from(UUID.randomUUID(), payload))
				.isInstanceOf(ApiException.class);
	}

	/** <b>조건이 붙지 않는다</b> (R7.16) — 조건은 템플릿에서 고르며 미리보기는 그 전이다. */
	@Test
	void R7_16_no_condition_is_invented() {
		StoryDefinition definition = DraftStoryDefinition.from(UUID.randomUUID(), PAYLOAD);

		assertThat(definition.chapters()).allSatisfy(
				chapter -> assertThat(chapter.entryConditionJson()).isNull());
		assertThat(definition.endings()).allSatisfy(
				ending -> assertThat(ending.conditionJson()).isNull());
	}
}

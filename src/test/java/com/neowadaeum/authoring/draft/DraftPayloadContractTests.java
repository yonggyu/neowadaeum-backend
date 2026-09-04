package com.neowadaeum.authoring.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.neowadaeum.catalog.publish.StoryDefinition;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * #354 — <b>원고가 쓰는 이름과 발행이 읽는 이름이 같다.</b>
 *
 * <p><b>이 픽스처가 이 파일의 전부다.</b> 아래 payload 는 마법사가 실제로 저장하는 모양이며,
 * 그 전까지의 픽스처는 <b>발행이 읽는 이름으로 쓰여 있었다</b> — 통합 테스트가 전부 초록인데도
 * 실제 제출은 {@code 400} 이었던 이유가 그것이다. 테스트가 계약이 아니라 <b>자기 자신을
 * 확인</b>하고 있었다.
 *
 * <p>이름의 정본은 계약의 {@code DraftPayload} 다. 여기서 하는 일은 그 이름으로 쓴 원고가
 * <b>실제로 발행되는지</b>를 보는 것이다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class DraftPayloadContractTests {

	/**
	 * 마법사가 저장하는 모양 그대로 (프론트 {@code stepFields.ts} · {@code outline.ts}).
	 *
	 * <p><b>고쳐 쓰지 않았다</b> — 고쳐 쓰는 순간 이 테스트는 다시 자기 자신을 확인하게 된다.
	 */
	private static final String WIZARD_PAYLOAD = """
			{"title":"봄의 학교",
			 "genres":["romance"],
			 "shortDescription":"한 줄 소개",
			 "coverImage":null,
			 "worldIntro":"독자에게 보이는 소개",
			 "settingDetail":"매 턴 모델에게 들어가는 문장이다.",
			 "characters":[{"name":"yuna","oneLine":"한 줄","portraitImage":null}],
			 "chapters":[{"chapterNo":1,"title":"1장","summarySeed":"시작",
			              "conditionTemplateKey":null,"conditionParams":{}}],
			 "endings":[{"endingNo":1,"label":"좋은 끝","epilogueText":"잘 끝났다.",
			             "isDefault":false,"conditionTemplateKey":"turn_at_least",
			             "conditionParams":{"threshold":10}}]}
			""";

	/**
	 * <b>제출이 400 이었다</b> (#354). 발행은 {@code worldPrompt} 를 필수로 읽었고 마법사는
	 * 그 이름을 쓴 적이 없다 — 실제 마법사로 UGC 를 낸 사람은 <b>한 번도 성공하지 못했다.</b>
	 */
	@Test
	void S354_the_payload_the_wizard_writes_is_publishable() {
		assertThatCode(() -> DraftStoryDefinition.from(UUID.randomUUID(), WIZARD_PAYLOAD))
				.doesNotThrowAnyException();
	}

	/** {@code settingDetail} 이 매 턴 모델에게 들어가는 문장이다 (#354). */
	@Test
	void S354_setting_detail_becomes_the_world_prompt() {
		StoryDefinition definition = DraftStoryDefinition
				.from(UUID.randomUUID(), WIZARD_PAYLOAD).definition();

		assertThat(definition.worldPrompt()).isEqualTo("매 턴 모델에게 들어가는 문장이다.");
	}

	/**
	 * <b>{@code shortDescription} 은 발행물이 이미 쓰는 이름이다</b> (`StoryDetail`, #354).
	 *
	 * <p>이 이름이 어긋나 있던 동안 소개는 <b>빈 채로 발행됐다</b> — 예외가 나지 않으므로
	 * 아무도 알아채지 못한다.
	 */
	@Test
	void S354_short_description_reaches_the_published_story() {
		StoryDefinition definition = DraftStoryDefinition
				.from(UUID.randomUUID(), WIZARD_PAYLOAD).definition();

		assertThat(definition.shortDesc()).isEqualTo("한 줄 소개");
	}

	/**
	 * <b>조건은 형제 필드로 온다</b> (#354, §13-69).
	 *
	 * <p>초안 응답이 {@code conditionTemplateKey} 를 그 높이에 돌려주므로, 저장이 한 겹 접으면
	 * 화면은 <b>받은 모양과 보내는 모양이 다른</b> 상태를 매번 변환해야 한다.
	 */
	@Test
	void S354_the_chosen_condition_is_read_from_sibling_fields() {
		StoryDefinition definition = DraftStoryDefinition
				.from(UUID.randomUUID(), WIZARD_PAYLOAD).definition();

		assertThat(definition.endings().getFirst().conditionJson())
				.isEqualTo("{\"turnGte\":10}");
	}

	/**
	 * <b>{@code null} 은 "고르지 않았다" 다</b> (#354).
	 *
	 * <p>화면은 조건을 지울 때 키를 지우는 대신 {@code null} 을 보낸다 — 그것을 오류로 읽으면
	 * <b>조건을 되돌릴 수 없다.</b>
	 */
	@Test
	void S354_a_null_template_key_means_no_condition() {
		StoryDefinition definition = DraftStoryDefinition
				.from(UUID.randomUUID(), WIZARD_PAYLOAD).definition();

		assertThat(definition.chapters().getFirst().entryConditionJson()).isNull();
	}
}

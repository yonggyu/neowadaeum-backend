package com.neowadaeum.play.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 생성 계약의 <b>형태</b>를 못박는다 (#84, ADR-0006).
 *
 * <p><b>여기서 보는 것은 동작이 아니라 자리다.</b> I-1 · I-9 · I-11 이 요구하는 것은 "서버가 AI 의
 * 값을 쓰지 않는다"이고, 그것을 지키는 가장 단단한 방법은 <b>담을 컴포넌트를 만들지 않는 것</b>이다.
 * 값을 무시하는 구현은 다음 사람이 되살릴 수 있지만, 없는 필드는 되살릴 수 없다.
 *
 * <p>이전에는 이 단언들이 {@code ai} 쪽 테스트에 있었다. 계약이 {@code play} 로 옮겨졌으므로
 * (ADR-0006) 계약을 지키는 테스트도 소유자 곁에 둔다.
 */
class GeneratedTurnContractTests {

	private static final UUID STORY = UUID.fromString("11111111-1111-4111-8111-111111111111");

	/**
	 * I-9 — {@code chapter} · {@code turn} 은 서버 전용이다. 생성 계약에 <b>자리 자체가 없어야</b>
	 * 한다. 값을 무시하는 구현은 다음 사람이 되살릴 수 있지만, 없는 필드는 되살릴 수 없다.
	 */
	@Test
	void I9_result_has_no_server_owned_chapter_or_turn_component() {
		List<String> components = componentNames(GeneratedTurn.class);

		assertThat(components).doesNotContain("chapter", "chapterNo", "turn", "turnNo");
	}

	/** I-1 · I-11 — choiceId 발급과 disabled 판정은 서버 몫이다. 제안 선택지는 order · text 뿐이다. */
	@Test
	void I1_I11_proposed_choice_carries_only_order_and_text() {
		assertThat(componentNames(GeneratedChoice.class)).containsExactly("order", "text");
	}

	/**
	 * I-3 — 회원 식별정보를 담을 필드가 존재하지 않는다.
	 *
	 * <p>화이트리스트 검증기(B-19)는 아직 없다. 그전까지 이 레코드의 <b>형태</b>가 유일한 보장이므로
	 * 필드가 늘어나면 이 테스트가 먼저 깨져야 한다.
	 */
	@Test
	void I3_request_has_no_component_that_could_carry_member_identity() {
		List<String> components = componentNames(TurnRequest.class);

		assertThat(components).containsExactly("storyVersionRef", "turnNo", "chosenChoiceOrder", "context");
		assertThat(components).doesNotContain("playerRef", "player_ref", "userId", "email",
				"birthDate", "ip", "socialId");

		// B-22 로 context 가 늘었다. 안쪽도 같은 성질을 지켜야 한다 — 회원 식별정보를 담을 자리는
		// 바깥이든 안쪽이든 없다. 중첩이 생겼다는 이유로 검사가 얕아지면 보장이 사라진다.
		assertThat(componentNames(GenerationContext.class))
				.containsExactly("worldPrompt", "characters", "gameState", "summary", "recentTurns", "userAction");
		assertThat(componentNames(GenerationContext.Character.class)).containsExactly("name", "persona");
		assertThat(componentNames(GenerationContext.RecentTurn.class))
				.containsExactly("turnNo", "chosenChoiceText", "paragraphs", "paragraphsDigest");
	}


	/** R5.1 · R5.2 — 문단은 종류 · 화자 · 본문 셋이다. 여기에 서버 판정 값이 끼어들 자리가 없다. */
	@Test
	void R5_1_paragraph_carries_only_type_speaker_and_text() {
		assertThat(componentNames(GeneratedParagraph.class)).containsExactly("type", "speakerName", "text");
	}

	/**
	 * I-11 — {@code disabled} · {@code disabledReason} 은 서버가 GameState 조건으로 판정한다.
	 * 계약 어디에도 자리가 없다.
	 */
	@Test
	void I11_no_type_in_the_contract_can_carry_a_server_judged_disabled_flag() {
		assertThat(componentNames(GeneratedChoice.class)).doesNotContain("disabled", "disabledReason");
		assertThat(componentNames(GeneratedTurn.class)).doesNotContain("disabled", "disabledReason");
	}

	/**
	 * R5.1 — <b>본문이 비어 있는 턴을 만들 수 없다.</b>
	 *
	 * <p>이전 계약은 통 문자열이었고 저장 직전에 {@code List.of(...)} 로 감쌌다. 그 형태가 돌아오면
	 * 여기가 아니라 저장 시점에야 드러난다.
	 */
	@Test
	void R5_1_a_generated_turn_cannot_be_built_without_paragraphs() {
		assertThatThrownBy(() -> new GeneratedTurn(List.of(), List.of(new GeneratedChoice(1, "계속한다")),
				JsonMapper.builder().build().readTree("{}"), false, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("paragraphs");
	}

	/** R5.2 — 나레이션은 화자가 없다. 대사는 화자를 가질 수 있다. */
	@Test
	void R5_2_narration_has_no_speaker_and_dialogue_may_have_one() {
		assertThat(GeneratedParagraph.narration("눈이 내렸다").speakerName()).isNull();
		assertThat(new GeneratedParagraph(ParagraphType.DIALOGUE, "유나", "왔네.").speakerName()).isEqualTo("유나");
	}

	/**
	 * {@code turn.speaker_name} 에 들어가는 값은 <b>파생값</b>이다 (#84 결정).
	 *
	 * <p>컬럼을 지우지 않고 남긴 이유는 한 턴에 여러 화자를 외부 계약이 실제로 지원하게 될 때
	 * 별도로 다루기 위해서다. 그때까지 진실의 원천은 문단 배열이다.
	 */
	@Test
	void S84_lead_speaker_is_derived_from_the_first_dialogue_paragraph() {
		GeneratedTurn turn = new GeneratedTurn(
				List.of(GeneratedParagraph.narration("복도 끝에서 발소리가 멈췄다."),
						new GeneratedParagraph(ParagraphType.DIALOGUE, "유나", "거기 서 있으면 문 못 열어."),
						new GeneratedParagraph(ParagraphType.DIALOGUE, "지훈", "비켜 줄게.")),
				List.of(new GeneratedChoice(1, "계속한다")),
				JsonMapper.builder().build().readTree("{}"), false, null);

		assertThat(turn.leadSpeakerName()).isEqualTo("유나");
	}

	/** 대사가 없는 턴에는 대표 화자가 없다 — 빈 문자열이 아니라 {@code null} 이다 (web-api 규칙). */
	@Test
	void S84_a_turn_without_dialogue_has_no_lead_speaker() {
		GeneratedTurn turn = new GeneratedTurn(List.of(GeneratedParagraph.narration("눈이 내렸다")),
				List.of(new GeneratedChoice(1, "계속한다")),
				JsonMapper.builder().build().readTree("{}"), false, null);

		assertThat(turn.leadSpeakerName()).isNull();
	}

	private static List<String> componentNames(Class<?> type) {
		return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
	}
}

package com.neowadaeum.ai.provider.fixed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.ai.provider.TurnRequest;
import com.neowadaeum.ai.provider.TurnResult;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-3 결정론 Provider 의 계약을 고정한다.
 *
 * <p>여기서 지키는 것은 "동작한다"가 아니라 <b>"AI 가 서버 권한을 넘겨받지 못한다"</b>이다.
 */
class FixedStoryProviderTests {

	private static final UUID FIXTURE_STORY = UUID.fromString("22222222-2222-4222-8222-222222222222");

	/** {@code src/main/resources/scenarios/demo-first-day.json} — 개발 도구로 배포되는 시나리오. */
	private static final UUID DEMO_STORY = UUID.fromString("11111111-1111-4111-8111-111111111111");

	private static FixedStoryProvider provider() {
		FixedStoryScenarioLoader loader = new FixedStoryScenarioLoader(JsonMapper.builder().build(),
				new org.springframework.core.io.support.PathMatchingResourcePatternResolver(),
				"classpath*:scenarios/s3-branching.json");
		return new FixedStoryProvider(loader.load());
	}

	/** I-15 — 같은 입력은 언제나 같은 출력. 난수가 개입할 자리가 없다. */
	@Test
	void I15_same_request_always_yields_the_same_result() {
		FixedStoryProvider provider = provider();
		TurnRequest request = new TurnRequest(FIXTURE_STORY, 1, 1);

		TurnResult first = provider.generateTurn(request);
		TurnResult second = provider.generateTurn(request);
		TurnResult third = provider.generateTurn(new TurnRequest(FIXTURE_STORY, 1, 1));

		assertThat(first).isEqualTo(second).isEqualTo(third);
	}

	/** I-15 — 분기는 고른 선택지로만 갈린다. 같은 턴이라도 다른 선택은 다른 응답이다. */
	@Test
	void I15_branching_is_decided_only_by_the_chosen_choice() {
		FixedStoryProvider provider = provider();

		TurnResult left = provider.generateTurn(new TurnRequest(FIXTURE_STORY, 1, 1));
		TurnResult right = provider.generateTurn(new TurnRequest(FIXTURE_STORY, 1, 2));

		assertThat(left.narrative()).isNotEqualTo(right.narrative());
	}

	/** B-44 선행 — 시작부터 엔딩까지 실제 AI 없이 재현된다. */
	@Test
	void B44_scenario_replays_from_opening_to_ending() {
		FixedStoryProvider provider = provider();

		TurnResult opening = provider.generateTurn(TurnRequest.opening(FIXTURE_STORY));
		assertThat(opening.choices()).hasSize(2);
		assertThat(opening.endingSuggested()).isNull();

		TurnResult middle = provider.generateTurn(new TurnRequest(FIXTURE_STORY, 1, 1));
		assertThat(middle.choices()).hasSize(1);

		TurnResult ending = provider.generateTurn(new TurnRequest(FIXTURE_STORY, 2, 1));
		assertThat(ending.choices()).isEmpty();
		assertThat(ending.endingSuggested()).isEqualTo("ending-test");
	}

	/**
	 * B-44 선행 — <b>실제로 배포되는</b> 개발 도구 시나리오가 엔딩까지 닿는다.
	 *
	 * <p>테스트 픽스처만 검증하면 {@code src/main/resources/scenarios/} 의 시나리오는 조용히 썩는다.
	 * 그때의 증상은 S-9·S-10 에서 "왜 여기서 막히지"로 나타나고, 원인이 시나리오 파일이라는 것을
	 * 찾는 데 시간이 든다.
	 */
	@Test
	void B44_shipped_dev_scenario_replays_to_an_ending() {
		FixedStoryScenarioLoader loader = new FixedStoryScenarioLoader(JsonMapper.builder().build(),
				new org.springframework.core.io.support.PathMatchingResourcePatternResolver(),
				"classpath*:scenarios/demo-first-day.json");
		FixedStoryProvider provider = new FixedStoryProvider(loader.load());

		assertThat(provider.generateTurn(TurnRequest.opening(DEMO_STORY)).choices()).hasSize(2);
		assertThat(provider.generateTurn(new TurnRequest(DEMO_STORY, 1, 1)).endingSuggested()).isNull();
		assertThat(provider.generateTurn(new TurnRequest(DEMO_STORY, 2, 1)).chapterAdvanceSuggested()).isTrue();

		TurnResult ending = provider.generateTurn(new TurnRequest(DEMO_STORY, 3, 1));

		assertThat(ending.choices()).isEmpty();
		assertThat(ending.endingSuggested()).isEqualTo("ending-first-light");
	}

	/** §0.2 — 미구현 경로를 스텁으로 통과시키지 않는다. 없는 턴은 지어내지 않고 던진다. */
	@Test
	void S0_2_unknown_request_throws_instead_of_inventing_a_turn() {
		FixedStoryProvider provider = provider();

		assertThatThrownBy(() -> provider.generateTurn(new TurnRequest(FIXTURE_STORY, 9, 1)))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	/** S-3 보안 요건 — 실패 메시지에 작품 본문이 실리지 않는다. 예외 메시지는 로그로 흐른다. */
	@Test
	void S3_failure_message_carries_coordinates_not_narrative_text() {
		FixedStoryProvider provider = provider();

		assertThatThrownBy(() -> provider.generateTurn(new TurnRequest(FIXTURE_STORY, 9, 1)))
				.hasMessageContaining("turnNo=9")
				.hasMessageNotContaining("왼쪽 길")
				.hasMessageNotContaining("길이 끝났다");
	}

	/** I-15 — 중복 키는 결과를 파일 순서에 맡기게 된다. 적재 시점에 거부한다. */
	@Test
	void I15_duplicate_scenario_entry_is_rejected_at_load_time() {
		FixedStoryScenario.Entry entry = new FixedStoryScenario.Entry(0, null, "본문",
				List.of(new FixedStoryScenario.Entry.Choice(1, "선택")), Map.of(), false, null);
		FixedStoryScenario scenario = new FixedStoryScenario(FIXTURE_STORY, "중복", List.of(entry, entry));

		assertThatThrownBy(() -> new FixedStoryProvider(List.of(scenario)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("duplicate");
	}

	/**
	 * I-9 — {@code chapter} · {@code turn} 은 서버 전용이다. Provider 응답에 <b>자리 자체가 없어야</b>
	 * 한다. 값을 무시하는 구현은 다음 사람이 되살릴 수 있지만, 없는 필드는 되살릴 수 없다.
	 */
	@Test
	void I9_result_has_no_server_owned_chapter_or_turn_component() {
		List<String> components = componentNames(TurnResult.class);

		assertThat(components).doesNotContain("chapter", "chapterNo", "turn", "turnNo");
	}

	/** I-1 · I-11 — choiceId 발급과 disabled 판정은 서버 몫이다. 제안 선택지는 order · text 뿐이다. */
	@Test
	void I1_I11_proposed_choice_carries_only_order_and_text() {
		assertThat(componentNames(TurnResult.ProposedChoice.class)).containsExactly("order", "text");
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

		assertThat(components).containsExactly("storyVersionRef", "turnNo", "chosenChoiceOrder");
		assertThat(components).doesNotContain("playerRef", "player_ref", "userId", "email", "name",
				"birthDate", "ip", "socialId");
	}

	/** §4.3 턴 번호 계약 — 첫 턴에는 고른 선택지가 없고, 이후 턴에는 반드시 있다. */
	@Test
	void S4_3_turn_number_contract_is_enforced_by_the_request_itself() {
		assertThatThrownBy(() -> new TurnRequest(FIXTURE_STORY, 0, 1))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new TurnRequest(FIXTURE_STORY, 1, null))
				.isInstanceOf(IllegalArgumentException.class);

		assertThat(TurnRequest.opening(FIXTURE_STORY).chosenChoiceOrder()).isNull();
	}

	private static List<String> componentNames(Class<?> type) {
		RecordComponent[] components = type.getRecordComponents();
		return Arrays.stream(components).map(RecordComponent::getName).toList();
	}
}

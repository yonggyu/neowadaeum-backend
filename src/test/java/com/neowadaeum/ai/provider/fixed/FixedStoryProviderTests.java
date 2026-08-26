package com.neowadaeum.ai.provider.fixed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.ai.provider.OutlineRequest;
import com.neowadaeum.ai.provider.ProviderCapabilities;
import com.neowadaeum.ai.provider.SummaryRequest;
import com.neowadaeum.play.port.TurnRequest;
import com.neowadaeum.play.port.GeneratedTurn;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import com.neowadaeum.play.port.GeneratedChoice;
import com.neowadaeum.play.port.GeneratedParagraph;
import java.util.List;
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

		GeneratedTurn first = provider.generateTurn(request);
		GeneratedTurn second = provider.generateTurn(request);
		GeneratedTurn third = provider.generateTurn(new TurnRequest(FIXTURE_STORY, 1, 1));

		assertThat(first).isEqualTo(second).isEqualTo(third);
	}

	/** I-15 — 분기는 고른 선택지로만 갈린다. 같은 턴이라도 다른 선택은 다른 응답이다. */
	@Test
	void I15_branching_is_decided_only_by_the_chosen_choice() {
		FixedStoryProvider provider = provider();

		GeneratedTurn left = provider.generateTurn(new TurnRequest(FIXTURE_STORY, 1, 1));
		GeneratedTurn right = provider.generateTurn(new TurnRequest(FIXTURE_STORY, 1, 2));

		assertThat(left.paragraphs()).isNotEqualTo(right.paragraphs());
	}

	/** B-44 선행 — 시작부터 엔딩까지 실제 AI 없이 재현된다. */
	@Test
	void B44_scenario_replays_from_opening_to_ending() {
		FixedStoryProvider provider = provider();

		GeneratedTurn opening = provider.generateTurn(TurnRequest.opening(FIXTURE_STORY));
		assertThat(opening.choices()).hasSize(2);
		assertThat(opening.endingSuggested()).isNull();

		GeneratedTurn middle = provider.generateTurn(new TurnRequest(FIXTURE_STORY, 1, 1));
		assertThat(middle.choices()).hasSize(1);

		GeneratedTurn ending = provider.generateTurn(new TurnRequest(FIXTURE_STORY, 2, 1));
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
		FixedStoryProvider provider = demoProvider();

		assertThat(provider.generateTurn(TurnRequest.opening(DEMO_STORY)).choices()).hasSize(2);
		assertThat(provider.generateTurn(new TurnRequest(DEMO_STORY, 1, 1)).endingSuggested()).isNull();
		assertThat(provider.generateTurn(new TurnRequest(DEMO_STORY, 2, 1)).chapterAdvanceSuggested()).isTrue();
		assertThat(provider.generateTurn(new TurnRequest(DEMO_STORY, 3, 1)).endingSuggested()).isNull();

		GeneratedTurn ending = provider.generateTurn(new TurnRequest(DEMO_STORY, 4, 1));

		assertThat(ending.choices()).isEmpty();
		assertThat(ending.endingSuggested()).isEqualTo("ending-first-light");
	}

	/**
	 * B-44 선행 — <b>두 갈래 모두</b> 끝까지 간다.
	 *
	 * <p>한 갈래만 완주하면 다른 갈래는 도중에 시나리오가 끊긴 채로 남고, S-9 에서
	 * "시나리오에 없는 요청"으로 터진다. 실제로 S-9-1 착수 때 그렇게 드러났다.
	 */
	@Test
	void B44_the_other_branch_also_replays_to_an_ending() {
		FixedStoryProvider provider = demoProvider();

		for (int turnNo = 1; turnNo <= 7; turnNo++) {
			assertThat(provider.generateTurn(new TurnRequest(DEMO_STORY, turnNo, 2)).endingSuggested())
					.as("%d 턴에서 끝나면 안 된다", turnNo)
					.isNull();
		}

		GeneratedTurn ending = provider.generateTurn(new TurnRequest(DEMO_STORY, 8, 2));

		assertThat(ending.choices()).isEmpty();
		assertThat(ending.endingSuggested()).isEqualTo("ending-quiet-exit");
	}

	private static FixedStoryProvider demoProvider() {
		FixedStoryScenarioLoader loader = new FixedStoryScenarioLoader(JsonMapper.builder().build(),
				new org.springframework.core.io.support.PathMatchingResourcePatternResolver(),
				"classpath*:scenarios/demo-first-day.json");
		return new FixedStoryProvider(loader.load());
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
		FixedStoryScenario.Entry entry = new FixedStoryScenario.Entry(0, null,
				List.of(GeneratedParagraph.narration("본문")), List.of(new GeneratedChoice(1, "선택")),
				JsonMapper.builder().build().readTree("{}"), false, null);
		FixedStoryScenario scenario = new FixedStoryScenario(FIXTURE_STORY, "중복", List.of(entry, entry));

		assertThatThrownBy(() -> new FixedStoryProvider(List.of(scenario)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("duplicate");
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

	/**
	 * §3 — 능력을 스스로 밝힌다. <b>모델을 부르지 않는 Provider 라는 사실이 값에 드러난다.</b>
	 *
	 * <p>{@code maxContextTokens} 가 {@code 0} 인 것은 상한이 없다는 뜻이 아니라 <b>프롬프트를
	 * 소비하지 않는다</b>는 뜻이다. 예산 계산(B-20)이 이 Provider 를 기준으로 이뤄지면 즉시 드러난다.
	 */
	@Test
	void B18_the_fixed_provider_reports_that_it_uses_no_model() {
		assertThat(provider().capabilities()).isEqualTo(ProviderCapabilities.withoutModel());
		assertThat(provider().capabilities().maxContextTokens()).isZero();
	}

	/**
	 * §0.2 — <b>구현하지 않은 용도를 스텁으로 통과시키지 않는다.</b>
	 *
	 * <p>빈 문자열을 돌려주는 {@code summarize} 하나면 요약 파이프라인(B-34)이 없는데도 초록이 된다.
	 */
	@Test
	void B18_unimplemented_uses_throw_instead_of_returning_something_plausible() {
		FixedStoryProvider provider = provider();

		assertThatThrownBy(() -> provider.summarize(
				new SummaryRequest(null, List.of(new SummaryRequest.TurnDigest(1, null, "요지")), 600)))
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> provider.draftOutline(new OutlineRequest("세계관", 5, 3)))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	private static List<String> componentNames(Class<?> type) {
		RecordComponent[] components = type.getRecordComponents();
		return Arrays.stream(components).map(RecordComponent::getName).toList();
	}
}

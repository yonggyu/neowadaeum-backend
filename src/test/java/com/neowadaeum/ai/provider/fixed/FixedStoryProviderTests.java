package com.neowadaeum.ai.provider.fixed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.ai.provider.OutlineRequest;
import com.neowadaeum.ai.provider.OutlineResult;
import com.neowadaeum.ai.provider.ProviderCapabilities;
import com.neowadaeum.play.port.SummaryRequest;
import com.neowadaeum.play.port.GenerationContexts;
import com.neowadaeum.play.port.TurnRequest;
import com.neowadaeum.play.port.GeneratedTurn;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import com.neowadaeum.common.spi.SafetyCategory;
import com.neowadaeum.common.support.FixedTokenCounter;
import com.neowadaeum.common.spi.SafetyClassificationRequest;
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
		return new FixedStoryProvider(loader.load(), new FixedTokenCounter());
	}

	/** I-15 — 같은 입력은 언제나 같은 출력. 난수가 개입할 자리가 없다. */
	@Test
	void I15_same_request_always_yields_the_same_result() {
		FixedStoryProvider provider = provider();
		TurnRequest request = TurnRequest.of(FIXTURE_STORY, 1, 1, GenerationContexts.sample());

		GeneratedTurn first = provider.generateTurn(request);
		GeneratedTurn second = provider.generateTurn(request);
		GeneratedTurn third = provider.generateTurn(TurnRequest.of(FIXTURE_STORY, 1, 1, GenerationContexts.sample()));

		assertThat(first).isEqualTo(second).isEqualTo(third);
	}

	/** I-15 — 분기는 고른 선택지로만 갈린다. 같은 턴이라도 다른 선택은 다른 응답이다. */
	@Test
	void I15_branching_is_decided_only_by_the_chosen_choice() {
		FixedStoryProvider provider = provider();

		GeneratedTurn left = provider.generateTurn(TurnRequest.of(FIXTURE_STORY, 1, 1, GenerationContexts.sample()));
		GeneratedTurn right = provider.generateTurn(TurnRequest.of(FIXTURE_STORY, 1, 2, GenerationContexts.sample()));

		assertThat(left.paragraphs()).isNotEqualTo(right.paragraphs());
	}

	/** B-44 선행 — 시작부터 엔딩까지 실제 AI 없이 재현된다. */
	@Test
	void B44_scenario_replays_from_opening_to_ending() {
		FixedStoryProvider provider = provider();

		GeneratedTurn opening = provider.generateTurn(TurnRequest.opening(FIXTURE_STORY, GenerationContexts.sample()));
		assertThat(opening.choices()).hasSize(2);
		assertThat(opening.endingSuggested()).isNull();

		GeneratedTurn middle = provider.generateTurn(TurnRequest.of(FIXTURE_STORY, 1, 1, GenerationContexts.sample()));
		assertThat(middle.choices()).hasSize(1);

		GeneratedTurn ending = provider.generateTurn(TurnRequest.of(FIXTURE_STORY, 2, 1, GenerationContexts.sample()));
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

		assertThat(provider.generateTurn(TurnRequest.opening(DEMO_STORY, GenerationContexts.sample())).choices()).hasSize(2);
		assertThat(provider.generateTurn(TurnRequest.of(DEMO_STORY, 1, 1, GenerationContexts.sample())).endingSuggested()).isNull();
		assertThat(provider.generateTurn(TurnRequest.of(DEMO_STORY, 2, 1, GenerationContexts.sample())).chapterAdvanceSuggested()).isTrue();
		assertThat(provider.generateTurn(TurnRequest.of(DEMO_STORY, 3, 1, GenerationContexts.sample())).endingSuggested()).isNull();

		GeneratedTurn ending = provider.generateTurn(TurnRequest.of(DEMO_STORY, 4, 1, GenerationContexts.sample()));

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
			assertThat(provider.generateTurn(TurnRequest.of(DEMO_STORY, turnNo, 2, GenerationContexts.sample())).endingSuggested())
					.as("%d 턴에서 끝나면 안 된다", turnNo)
					.isNull();
		}

		GeneratedTurn ending = provider.generateTurn(TurnRequest.of(DEMO_STORY, 8, 2, GenerationContexts.sample()));

		assertThat(ending.choices()).isEmpty();
		assertThat(ending.endingSuggested()).isEqualTo("ending-quiet-exit");
	}

	private static FixedStoryProvider demoProvider() {
		FixedStoryScenarioLoader loader = new FixedStoryScenarioLoader(JsonMapper.builder().build(),
				new org.springframework.core.io.support.PathMatchingResourcePatternResolver(),
				"classpath*:scenarios/demo-first-day.json");
		return new FixedStoryProvider(loader.load(), new FixedTokenCounter());
	}

	/** §0.2 — 미구현 경로를 스텁으로 통과시키지 않는다. 없는 턴은 지어내지 않고 던진다. */
	@Test
	void S0_2_unknown_request_throws_instead_of_inventing_a_turn() {
		FixedStoryProvider provider = provider();

		assertThatThrownBy(() -> provider.generateTurn(TurnRequest.of(FIXTURE_STORY, 9, 1, GenerationContexts.sample())))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	/** S-3 보안 요건 — 실패 메시지에 작품 본문이 실리지 않는다. 예외 메시지는 로그로 흐른다. */
	@Test
	void SEC3_failure_message_carries_coordinates_not_narrative_text() {
		FixedStoryProvider provider = provider();

		assertThatThrownBy(() -> provider.generateTurn(TurnRequest.of(FIXTURE_STORY, 9, 1, GenerationContexts.sample())))
				.hasMessageContaining("turnNo=9")
				.hasMessageNotContaining("왼쪽 길")
				.hasMessageNotContaining("길이 끝났다");
	}

	/** I-15 — 중복 키는 결과를 파일 순서에 맡기게 된다. 적재 시점에 거부한다. */
	@Test
	void I15_duplicate_scenario_entry_is_rejected_at_load_time() {
		FixedStoryScenario.Entry entry = new FixedStoryScenario.Entry(0, null,
				List.of(GeneratedParagraph.narration("본문")), List.of(new GeneratedChoice(1, "선택")),
				JsonMapper.builder().build().readTree("{}"), false, null, List.of());
		FixedStoryScenario scenario = new FixedStoryScenario(FIXTURE_STORY, "중복", List.of(entry, entry));

		assertThatThrownBy(() -> new FixedStoryProvider(List.of(scenario), new FixedTokenCounter()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("duplicate");
	}

	/** §4.3 턴 번호 계약 — 첫 턴에는 고른 선택지가 없고, 이후 턴에는 반드시 있다. */
	@Test
	void S4_3_turn_number_contract_is_enforced_by_the_request_itself() {
		assertThatThrownBy(() -> TurnRequest.of(FIXTURE_STORY, 0, 1, GenerationContexts.sample()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> TurnRequest.of(FIXTURE_STORY, 1, null, GenerationContexts.sample()))
				.isInstanceOf(IllegalArgumentException.class);

		assertThat(TurnRequest.opening(FIXTURE_STORY, GenerationContexts.sample()).chosenChoiceOrder()).isNull();
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
	 * B-52 — <b>초안은 결정론이다</b> (I-15, S-3).
	 *
	 * <p>이 자리는 원래 §0.2 의 *"구현하지 않은 용도를 스텁으로 통과시키지 않는다"* 를 지켰다 —
	 * {@code draftOutline} 이 던졌기 때문이다. B-52 가 그것을 구현했으므로 이제 확인할 것은
	 * <b>구현된 것의 성질</b>이다: 요청한 수만큼 나오고, 같은 요청에는 같은 답이 나온다.
	 *
	 * <p>세계관에서 만들어 낸 것이 아니라 <b>자리를 채운 것</b>이다 — 이 Provider 는 시나리오를
	 * 읽어 답하는 개발 도구이며 초안에는 시나리오가 없다. 그래도 구조가 진짜여야 작성 흐름을
	 * 끝까지 눌러 볼 수 있다.
	 */
	@Test
	void B52_the_outline_is_deterministic_and_sized_as_asked() {
		OutlineResult first = provider().draftOutline(new OutlineRequest("세계관", 5, 3));
		OutlineResult again = provider().draftOutline(new OutlineRequest("세계관", 5, 3));

		assertThat(first.chapters()).hasSize(5);
		assertThat(first.endings()).hasSize(3);
		assertThat(first).isEqualTo(again);
	}

	/**
	 * <b>R4.5 — 예산을 넘으면 실제로 짧아진다.</b>
	 *
	 * <p>재압축이 의미를 가지려면 압축이 실제로 일어나야 한다. 빈 문자열을 돌려주는 스텁이었다면
	 * 이 테스트를 쓸 수 없다 (§0.2).
	 */
	@Test
	void R4_5_the_extractive_summary_drops_the_oldest_lines_to_fit_the_budget() {
		FixedStoryProvider provider = provider();
		List<SummaryRequest.TurnDigest> turns = List.of(
				new SummaryRequest.TurnDigest(1, null, "첫 턴의 요지"),
				new SummaryRequest.TurnDigest(2, "왼쪽", "둘째 턴의 요지"),
				new SummaryRequest.TurnDigest(3, "오른쪽", "셋째 턴의 요지"));

		String generous = provider.summarize(new SummaryRequest(null, turns, 600));
		String tight = provider.summarize(new SummaryRequest(null, turns, 8));

		assertThat(generous).contains("첫 턴의 요지").contains("셋째 턴의 요지");
		assertThat(tight)
				.as("예산이 좁으면 오래된 쪽부터 버린다")
				.doesNotContain("첫 턴의 요지")
				.contains("셋째 턴의 요지");
	}

	/** I-15 — 같은 입력에 같은 요약이다. 지어내는 부분이 없다. */
	@Test
	void I15_the_extractive_summary_is_deterministic() {
		FixedStoryProvider provider = provider();
		SummaryRequest request = new SummaryRequest("지난 줄거리",
				List.of(new SummaryRequest.TurnDigest(4, "선택", "넷째 턴의 요지")), 600);

		assertThat(provider.summarize(request)).isEqualTo(provider.summarize(request));
		assertThat(provider.summarize(request)).contains("지난 줄거리").contains("넷째 턴의 요지");
	}

	private static List<String> componentNames(Class<?> type) {
		RecordComponent[] components = type.getRecordComponents();
		return Arrays.stream(components).map(RecordComponent::getName).toList();
	}

	/**
	 * <b>I-15 — 판정 결과도 결정론이다</b> (B-30).
	 *
	 * <p>시나리오가 선언한 카테고리가 그대로 돌아온다. 세이프티 경로를 E2E 로 재현하려면 이것이
	 * 있어야 한다 — 결정론 Provider 에게 "모델이라면 뭐라고 답할까"를 지어내게 하지 않는다.
	 */
	@Test
	void I15_the_scenario_declares_what_the_judge_sees() {
		FixedStoryScenario.Entry flagged = new FixedStoryScenario.Entry(0, null,
				List.of(GeneratedParagraph.narration("걸리는 문단")), List.of(new GeneratedChoice(1, "선택")),
				JsonMapper.builder().build().readTree("{}"), false, null,
				List.of(SafetyCategory.RATING_EXCEEDED));
		FixedStoryProvider provider = new FixedStoryProvider(
				List.of(new FixedStoryScenario(FIXTURE_STORY, "판정 선언", List.of(flagged))), new FixedTokenCounter());

		assertThat(provider.classifySafety(new SafetyClassificationRequest(List.of("걸리는 문단"))))
				.containsExactly(SafetyCategory.RATING_EXCEEDED);
		assertThat(provider.classifySafety(new SafetyClassificationRequest(List.of("걸리는 문단"))))
				.as("같은 입력에 같은 결과가 아니면 결정론이 아니다")
				.containsExactly(SafetyCategory.RATING_EXCEEDED);
	}

	/** 선언이 없는 텍스트에는 아무것도 걸리지 않는다 — 1단(블록리스트)은 그와 무관하게 돈다. */
	@Test
	void B30_an_undeclared_text_has_no_categories() {
		FixedStoryScenario.Entry plain = new FixedStoryScenario.Entry(0, null,
				List.of(GeneratedParagraph.narration("평범한 문단")), List.of(new GeneratedChoice(1, "선택")),
				JsonMapper.builder().build().readTree("{}"), false, null, List.of());
		FixedStoryProvider provider = new FixedStoryProvider(
				List.of(new FixedStoryScenario(FIXTURE_STORY, "선언 없음", List.of(plain))), new FixedTokenCounter());

		assertThat(provider.classifySafety(new SafetyClassificationRequest(List.of("평범한 문단")))).isEmpty();
	}
}

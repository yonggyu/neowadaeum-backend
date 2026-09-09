package com.neowadaeum.ai.provider.anthropic;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.neowadaeum.ai.log.AiCallLog;
import com.neowadaeum.ai.prompt.PromptAssembler;
import com.neowadaeum.ai.prompt.TurnPromptFactory;
import com.neowadaeum.ai.provider.ProviderProperties;
import com.neowadaeum.ai.schema.TurnOutputParser;
import com.neowadaeum.common.spi.SafetyCategory;
import com.neowadaeum.common.spi.SafetyClassificationFailedException;
import com.neowadaeum.common.spi.SafetyClassificationRequest;
import com.neowadaeum.common.support.FixedTokenCounter;
import com.neowadaeum.common.support.RecentTurnsProperties;
import com.neowadaeum.play.port.ProviderCallFailedException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Anthropic 어댑터의 세이프티 판정 계약 (B-30, R9.2).
 *
 * <p><b>실제 AI 를 부르지 않는다</b> ({@code .claude/rules/testing.md}). 확인하는 것은 <b>어느
 * 모델로 무엇을 보내고, 무엇을 통과시키지 않는가</b>다.
 *
 * <p><b>기록을 볼 때 {@code getFirst()} 를 쓰지 않는다</b> (이슈 #446). 리스트가 비어 있으면 그
 * 호출은 {@link java.util.NoSuchElementException} 을 던지고 <b>왜 비었는지 아무것도 말하지
 * 않는다</b> — 판정이 기록되지 않은 것인지, 호출 자체가 나가지 못한 것인지, stub 이 매칭되지
 * 않아 다른 응답이 온 것인지가 전부 같은 예외가 된다. 이 클래스가 전량 실행에서 <b>한 번</b>
 * 실패했을 때 남은 것이 그 예외뿐이라 원인을 좁히지 못했다.
 *
 * <p>그래서 기록은 <b>리스트를 통째로 단언한다.</b> 실패 메시지가 *"크기가 1이어야 하는데
 * 0이다"* 를 말하고, {@code as(...)} 가 그 상태에서 무엇을 의심해야 하는지 함께 남긴다.
 * <b>재현되지 않는 실패에 대비하는 방법은 다음 실패가 스스로 설명하게 만드는 것뿐이다.</b>
 *
 * <p><b>그 대비의 나머지 절반이 HTTP 왕복이다</b> (이슈 #446, 2차). 판정 호출이 예외로 끝나면
 * 남는 것은 스택트레이스뿐이라 <b>서버가 요청을 받기는 했는지, 받았는데 stub 에 매칭되지
 * 않은 것인지</b>가 구분되지 않는다 — 앞의 것이면 연결·타이밍이고 뒤의 것이면 stub 이다.
 * {@link #serverSaw()} 가 그 둘을 갈라 실패 메시지에 싣는다.
 *
 * <p><b>36회를 돌려도 재현되지 않았다</b>(단독 15 · {@code clean} 전량 15 · 8코어 포화 6).
 * 병렬 실행 · 비동기 기록 · 인스턴스 공유 · 타임아웃 · 러너 리소스 압박이 모두 후보에서
 * 지워졌고 <b>원인은 여전히 미상이다.</b> 그래서 이 클래스가 하는 일은 원인을 막는 것이
 * 아니라 <b>다음 회차가 스스로를 설명하게 하는 것</b>이다.
 */
class AnthropicSafetyClassificationTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String TURN_MODEL = "claude-opus-5";

	private static final String SAFETY_MODEL = "claude-haiku-4-5";

	private final List<AiCallLog.Draft> recorded = Collections.synchronizedList(new ArrayList<>());

	private WireMockServer server;

	private AnthropicStoryProvider provider;

	@BeforeEach
	void startServer() {
		this.server = new WireMockServer(WireMockConfiguration.options().dynamicPort().http2PlainDisabled(true));
		this.server.start();
		this.provider = adapter(new AnthropicProperties.Models(TURN_MODEL, null, SAFETY_MODEL, null));
	}

	@AfterEach
	void stopServer() {
		this.server.stop();
	}

	private AnthropicStoryProvider adapter(AnthropicProperties.Models models) {
		AnthropicProperties properties = new AnthropicProperties("test-key", models,
				"http://localhost:" + this.server.port(), 4096, null);
		return new AnthropicStoryProvider(
				AnthropicProviderConfiguration.restClient(properties, new ProviderProperties(null, null)),
				properties,
				new TurnPromptFactory(new PromptAssembler(new FixedTokenCounter(), RecentTurnsProperties.defaults())),
				new TurnOutputParser(),
				this.recorded::add);
	}

	private void respondWith(String verdictJson) {
		this.server.stubFor(post(urlEqualTo("/v1/messages")).willReturn(aResponse()
				.withStatus(200)
				.withHeader("content-type", "application/json")
				.withBody("{\"content\":[{\"type\":\"text\",\"text\":%s}]}"
						.formatted(JSON.writeValueAsString(verdictJson)))));
	}

	private static SafetyClassificationRequest request() {
		return new SafetyClassificationRequest(List.of("복도 끝에서 발소리가 멈췄다.", "비켜 준다"));
	}

	/**
	 * 판정을 부르되, <b>실패하면 서버가 무엇을 받았는지 함께 말한다</b> (이슈 #446).
	 *
	 * <p>이 호출이 예외로 끝나는 길은 둘이다 — 벤더에 닿지 못했거나(연결·읽기), 닿았는데 응답이
	 * 판정으로 읽히지 않았거나. <b>스택트레이스만으로는 그 둘이 구분되지 않는다.</b> 서버가 받은
	 * 요청 수와 매칭되지 않은 요청 수가 그것을 가른다: <b>0건이면 요청이 나가지 못한 것이고,
	 * 매칭되지 않은 것이 있으면 stub 이 어긋난 것이다.</b>
	 *
	 * <p>성공 경로에서는 아무것도 하지 않는다 — 진단은 실패했을 때만 필요하다.
	 */
	private Set<SafetyCategory> classifyOrExplain() {
		try {
			return this.provider.classifySafety(request());
		}
		catch (RuntimeException failure) {
			throw new AssertionError("판정 호출이 실패했다 (#446). " + serverSaw(), failure);
		}
	}

	/**
	 * 서버가 본 것을 한 줄로. <b>본문을 싣지 않는다</b> — 수와 URL 만으로 두 갈래가 갈리고,
	 * 판정 대상 텍스트를 실패 메시지에 남기지 않는다 (S-11).
	 */
	private String serverSaw() {
		List<LoggedRequest> unmatched = this.server.findAllUnmatchedRequests();
		return "서버가 받은 요청 %d 건, 그중 stub 에 매칭되지 않은 것 %d 건%s — 0 건이면 요청이 나가지 못한 것이고, 매칭되지 않은 것이 있으면 stub 이 어긋난 것이다"
				.formatted(this.server.getAllServeEvents().size(), unmatched.size(),
						unmatched.isEmpty() ? "" : " " + unmatched.stream()
								.map(LoggedRequest::getUrl).distinct().toList());
	}

	/**
	 * <b>I-12 — 판정은 생성 모델이 하지 않는다.</b>
	 *
	 * <p>이 테스트가 이 작업의 핵심이다. 같은 어댑터가 부르더라도 <b>모델이 갈린다</b> — 그것이
	 * "생성 모델과 별개의 판정기"가 코드에서 뜻하는 것이다 (R3.6 의 용도 분리가 그 자리를 만들었다).
	 */
	@Test
	void I12_the_verdict_call_uses_the_safety_model_not_the_turn_model() {
		respondWith("{\"categories\": []}");

		this.provider.classifySafety(request());

		String sent = this.server.getAllServeEvents().getFirst().getRequest().getBodyAsString();
		assertThat(JSON.readTree(sent).path("model").asString(""))
				.isEqualTo(SAFETY_MODEL)
				.isNotEqualTo(TURN_MODEL);
	}

	/** 검수용 모델이 없으면 <b>턴 생성 모델을 빌려 쓰지 않는다</b> — 부르는 자리에서 실패한다 (R3.6). */
	@Test
	void R3_6_a_missing_safety_model_fails_the_call_instead_of_borrowing_the_turn_model() {
		AnthropicStoryProvider withoutSafetyModel = adapter(
				new AnthropicProperties.Models(TURN_MODEL, null, null, null));

		assertThatThrownBy(() -> withoutSafetyModel.classifySafety(request()))
				.isInstanceOf(ProviderCallFailedException.class);

		assertThat(this.server.getAllServeEvents()).as("모델이 없는데 호출이 나갔다").isEmpty();
	}

	/** I-3 — 나가는 것은 판정 대상 텍스트뿐이다. 세션·작품 식별자가 실리지 않는다. */
	@Test
	void I3_the_classification_payload_carries_only_the_judged_texts() {
		respondWith("{\"categories\": []}");
		UUID storyVersion = UUID.randomUUID();

		this.provider.classifySafety(request());

		String sent = this.server.getAllServeEvents().getFirst().getRequest().getBodyAsString();
		assertThat(sent)
				.contains("복도 끝에서 발소리가 멈췄다.")
				.contains("비켜 준다")
				.doesNotContain(storyVersion.toString())
				.doesNotContain("playerRef")
				.doesNotContain("sessionId");
	}

	/** R9.3 — 판정 결과가 {@code ai_call_log} 에 남는다. 무엇이 걸려 차단됐는지가 사후 단서다. */
	@Test
	void R9_3_the_verdict_is_recorded_with_the_safety_purpose_and_flags() {
		respondWith("{\"categories\": [\"hate_speech\"]}");

		assertThat(this.provider.classifySafety(request())).containsExactly(SafetyCategory.HATE_SPEECH);

		assertThat(this.recorded)
				.as("판정 호출이 기록되지 않았다 (#446) — 호출이 나가지 못했거나 stub 이 매칭되지 않았다")
				.singleElement()
				.satisfies(draft -> {
					assertThat(draft.purpose()).isEqualTo("safety");
					assertThat(draft.modelId()).isEqualTo(SAFETY_MODEL);
					assertThat(draft.safetyFlags()).isEqualTo("hate_speech");
				});
	}

	/** 걸린 것이 없으면 빈 집합이고 {@code safety_flags} 는 비어 있다. */
	@Test
	void R9_2_a_clean_verdict_is_an_empty_set() {
		respondWith("{\"categories\": []}");

		assertThat(classifyOrExplain()).isEmpty();
		assertThat(this.recorded)
				.as("판정 호출이 기록되지 않았다 (#446) — 호출이 나가지 못했거나 stub 이 매칭되지 않았다")
				.singleElement()
				.extracting(AiCallLog.Draft::safetyFlags)
				.as("걸린 것이 없으면 safety_flags 는 비어 있다 (R9.2)")
				.isNull();
	}

	/**
	 * <b>형식 위반은 판정 실패다 — 승계 대상이 아니다.</b>
	 *
	 * <p>{@link SafetyClassificationFailedException} 은 fallback 체인이 잡지 않으므로 그대로
	 * 올라가 차단이 된다 (fail-closed). 기록은 남는다 — 무엇을 돌려줬길래 거부됐는지가 단서다.
	 */
	@Test
	void B30_a_malformed_verdict_is_a_classification_failure_and_is_still_recorded() {
		respondWith("판정할 수 없습니다");

		assertThatThrownBy(() -> this.provider.classifySafety(request()))
				.isInstanceOf(SafetyClassificationFailedException.class);

		assertThat(this.recorded)
				.as("형식 위반도 기록은 남는다 — 무엇을 돌려줬길래 거부됐는지가 단서다 (R9.3)")
				.singleElement()
				.extracting(AiCallLog.Draft::responseRaw)
				.isNotNull();
	}

	/**
	 * <b>벤더 장애는 판정 실패가 아니라 호출 실패다.</b>
	 *
	 * <p>둘을 나누는 이유는 <b>다음 벤더에게 물어볼 수 있는가</b>가 갈리기 때문이다 (ADR-0007).
	 * 죽은 벤더는 승계로 살아나지만, 형식을 못 맞춘 판정은 승계해도 같은 문제일 수 있다.
	 */
	@Test
	void ADR0007_a_dead_vendor_is_a_provider_failure_so_the_chain_can_succeed_it() {
		this.server.stubFor(post(urlEqualTo("/v1/messages")).willReturn(aResponse().withStatus(503)));

		assertThatThrownBy(() -> this.provider.classifySafety(request()))
				.isInstanceOf(ProviderCallFailedException.class);
	}
}

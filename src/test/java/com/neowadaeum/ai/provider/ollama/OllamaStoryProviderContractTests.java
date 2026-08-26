package com.neowadaeum.ai.provider.ollama;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.neowadaeum.ai.log.AiCallLog;
import com.neowadaeum.ai.prompt.PromptAssembler;
import com.neowadaeum.ai.prompt.TurnPromptFactory;
import com.neowadaeum.ai.provider.GenerationBudgets;
import com.neowadaeum.ai.provider.SchemaRetryingStoryProvider;
import com.neowadaeum.ai.provider.SummaryRequest;
import com.neowadaeum.ai.schema.TurnOutputParser;
import com.neowadaeum.common.spi.SafetyCategory;
import com.neowadaeum.common.spi.SafetyClassificationFailedException;
import com.neowadaeum.common.spi.SafetyClassificationRequest;
import com.neowadaeum.common.support.FixedTokenCounter;
import com.neowadaeum.common.support.RecentTurnsProperties;
import com.neowadaeum.play.port.GeneratedTurn;
import com.neowadaeum.play.port.GenerationContexts;
import com.neowadaeum.play.port.OutputSchemaRejectedException;
import com.neowadaeum.play.port.ProviderCallFailedException;
import com.neowadaeum.play.port.TurnRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ollama 어댑터 계약 테스트 (B-23, R3.2 · R3.3).
 *
 * <p><b>이 어댑터의 핵심은 {@code structuredOutput = false} 다.</b> 그 한 줄이 재요청 횟수를
 * 바꾸며, 스키마를 강제할 수단이 프롬프트뿐인 로컬 모델에 <b>한 번 더 기회를 준다.</b>
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class OllamaStoryProviderContractTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String VALID_TURN = """
			{"paragraphs":[{"type":"narration","text":"눈이 내렸다."}],
			 "choices":[{"order":1,"text":"걷는다"}]}
			""";

	private WireMockServer server;

	private OllamaStoryProvider provider;

	private final List<AiCallLog.Draft> recorded = Collections.synchronizedList(new ArrayList<>());

	@BeforeEach
	void startServer() {
		this.server = new WireMockServer(WireMockConfiguration.options().dynamicPort().http2PlainDisabled(true));
		this.server.start();

		OllamaProperties properties = new OllamaProperties("http://localhost:" + this.server.port(),
				new OllamaProperties.Models("llama3.1", "llama3.2:1b", "llama-guard3", null));

		this.provider = new OllamaStoryProvider(
				RestClient.builder().baseUrl(properties.baseUrl()).build(), properties,
				new TurnPromptFactory(new PromptAssembler(new FixedTokenCounter(), RecentTurnsProperties.defaults())),
				new TurnOutputParser(), this.recorded::add);
	}

	@AfterEach
	void stopServer() {
		this.server.stop();
	}

	private static TurnRequest request() {
		return TurnRequest.opening(UUID.randomUUID(), GenerationContexts.populated());
	}

	private void respondWith(String content) {
		this.server.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse()
				.withStatus(200).withHeader("content-type", "application/json")
				.withBody("{\"message\":{\"role\":\"assistant\",\"content\":%s}}"
						.formatted(JSON.writeValueAsString(content)))));
	}

	/** 정상 응답이 {@code GeneratedTurn} 이 된다 — 파서를 거친다 (B-21). */
	@Test
	void R3_2_a_valid_response_becomes_a_generated_turn() {
		respondWith(VALID_TURN);

		GeneratedTurn turn = this.provider.generateTurn(request());

		assertThat(turn.paragraphs()).hasSize(1);
		assertThat(turn.choices()).hasSize(1);
	}

	/**
	 * <b>R3.3 — {@code structuredOutput = false} 라 재요청이 2회다.</b> 이 작업의 DoD 다.
	 *
	 * <p>{@code true} 로 보고하면 1회만 주고, <b>형식을 못 맞춘 로컬 모델이 한 번 더 기회를 받지
	 * 못한다.</b> 반대로 승계까지 더하면 한 턴의 비용 상한이 사라진다 (ADR-0007).
	 */
	@Test
	void R3_3_an_unstructured_provider_retries_twice_then_errors() {
		respondWith("{\"paragraphs\": \"통 문자열 본문\"}");

		SchemaRetryingStoryProvider retrying = new SchemaRetryingStoryProvider(this.provider);

		// 재요청은 생성 예산 안쪽에서만 돈다 (§13-19, B-21-2). 운영에서 그 예산을 여는 것은
		// TimeLimitedStoryProvider 이고, 여기서는 같은 크기의 예산을 열어 같은 경로를 지난다.
		assertThatThrownBy(() -> GenerationBudgets.withinContractBudget(() -> retrying.generateTurn(request())))
				.isInstanceOf(OutputSchemaRejectedException.class);

		assertThat(this.server.getAllServeEvents())
				.as("R3.3 — 최초 1회 + 재요청 2회")
				.hasSize(3);
		assertThat(this.recorded).extracting(AiCallLog.Draft::attemptNo)
				.as("재요청이 별개 행으로 남고 번호가 갈린다 (B-25)")
				.containsExactly(1, 2, 3);
	}

	/**
	 * <b>I-12 · I-13 — 로컬 모델을 붙여도 판정은 서버가 하고, 판정 모델은 생성 모델과 갈린다.</b>
	 *
	 * <p>무검열 로컬 모델이 붙는 자리라서 이 성질이 특히 중요하다 (R3.4). 판정을 provider 에게
	 * 맡기는 것이 아니라 <b>서버가 판정용 모델을 따로 부른다.</b>
	 */
	@Test
	void I12_the_verdict_call_uses_the_safety_model() {
		this.server.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse()
				.withStatus(200)
				.withHeader("content-type", "application/json")
				.withBody("{\"message\":{\"content\":\"{\\\"categories\\\": [\\\"rating_exceeded\\\"]}\"}}")));

		assertThat(this.provider.classifySafety(new SafetyClassificationRequest(List.of("검수 대상 문장"))))
				.containsExactly(SafetyCategory.RATING_EXCEEDED);

		String sent = this.server.getAllServeEvents().getFirst().getRequest().getBodyAsString();
		assertThat(JSON.readTree(sent).path("model").asString(""))
				.isEqualTo("llama-guard3")
				.isNotEqualTo("llama3.1");
		assertThat(this.recorded.getFirst().purpose()).isEqualTo("safety");
		assertThat(this.recorded.getFirst().safetyFlags()).isEqualTo("rating_exceeded");
	}

	/**
	 * <b>형식을 못 맞추면 통과가 아니라 판정 실패다</b> (fail-closed).
	 *
	 * <p>턴 생성에서는 형식 위반이 재요청으로 이어지지만(R3.3) 판정에서는 그러지 않는다 — 판정하지
	 * 못한 응답은 사용자에게 도달하지 않아야 한다 (I-2).
	 */
	@Test
	void B30_a_malformed_verdict_is_not_retried_and_does_not_pass() {
		this.server.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse()
				.withStatus(200)
				.withHeader("content-type", "application/json")
				.withBody("{\"message\":{\"content\":\"모르겠습니다\"}}")));

		assertThatThrownBy(() -> this.provider.classifySafety(new SafetyClassificationRequest(List.of("문장"))))
				.isInstanceOf(SafetyClassificationFailedException.class);

		assertThat(this.server.getAllServeEvents()).as("판정에는 재요청을 걸지 않는다").hasSize(1);
	}

	/** I-7 — {@code SYSTEM} 이 작품 입력과 같은 평면에 놓이지 않는다. 형식만 다르고 이유는 같다. */
	@Test
	void I7_the_system_layer_is_its_own_message() {
		respondWith(VALID_TURN);

		this.provider.generateTurn(request());

		JsonNode messages = sentBody().path("messages");
		assertThat(messages.get(0).path("role").asString()).isEqualTo("system");
		assertThat(messages.get(0).path("content").asString()).contains("15세 이용가");
		assertThat(messages.get(1).path("content").asString())
				.as("SYSTEM 이 사용자 메시지에도 들어가면 분리한 의미가 없다")
				.doesNotContain("15세 이용가");
	}

	/** {@code structuredOutput} 을 정확히 보고한다 — 이 값 하나가 재요청 횟수를 정한다. */
	@Test
	void R3_3_the_adapter_reports_no_structured_output_support() {
		assertThat(this.provider.capabilities().structuredOutput()).isFalse();
		assertThat(this.provider.providerId()).isEqualTo(OllamaStoryProvider.PROVIDER_ID);
	}

	private JsonNode sentBody() {
		return JSON.readTree(this.server.getAllServeEvents().getFirst().getRequest().getBodyAsString());
	}

	/**
	 * <b>R3.6 — 요약도 자기 모델로 한다</b> (B-34).
	 *
	 * <p>로컬 실행이라 청구서는 없지만 시간과 GPU 는 같은 자원이다. 사용자가 기다리지 않는 호출이
	 * 턴 생성 모델을 붙들면 <b>다음 턴이 느려진다.</b>
	 */
	@Test
	void R3_6_the_summary_call_uses_the_summary_model() {
		this.server.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse()
				.withStatus(200)
				.withHeader("content-type", "application/json")
				.withBody("{\"message\":{\"content\":\"압축된 줄거리\"}}")));

		String summary = this.provider.summarize(new SummaryRequest("지난 줄거리",
				List.of(new SummaryRequest.TurnDigest(9, "왼쪽", "아홉째 턴의 요지")), 600));

		assertThat(summary).isEqualTo("압축된 줄거리");
		String sent = this.server.getAllServeEvents().getFirst().getRequest().getBodyAsString();
		assertThat(JSON.readTree(sent).path("model").asString(""))
				.isEqualTo("llama3.2:1b")
				.isNotEqualTo("llama3.1");
		assertThat(this.recorded.getFirst().purpose()).isEqualTo("summary");
	}

	/** 빈 요약을 저장하지 않는다 — 그 세션은 그 뒤로 아무것도 기억하지 못하게 된다. */
	@Test
	void R4_5_a_blank_summary_is_a_call_failure() {
		this.server.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse()
				.withStatus(200)
				.withHeader("content-type", "application/json")
				.withBody("{\"message\":{\"content\":\"  \"}}")));

		assertThatThrownBy(() -> this.provider.summarize(new SummaryRequest(null,
				List.of(new SummaryRequest.TurnDigest(9, null, "요지")), 600)))
				.isInstanceOf(ProviderCallFailedException.class);
	}
}

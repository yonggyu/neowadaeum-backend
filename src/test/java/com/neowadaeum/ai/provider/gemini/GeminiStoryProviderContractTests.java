package com.neowadaeum.ai.provider.gemini;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.neowadaeum.ai.log.AiCallLog;
import com.neowadaeum.ai.prompt.PromptAssembler;
import com.neowadaeum.ai.prompt.TurnPromptFactory;
import com.neowadaeum.ai.provider.OutlineRequest;
import com.neowadaeum.ai.provider.ProviderProperties;
import com.neowadaeum.ai.schema.TurnOutputParser;
import com.neowadaeum.ai.schema.TurnOutputSchemaException;
import com.neowadaeum.common.spi.SafetyCategory;
import com.neowadaeum.common.spi.SafetyClassificationRequest;
import com.neowadaeum.common.support.FixedTokenCounter;
import com.neowadaeum.common.support.RecentTurnsProperties;
import com.neowadaeum.play.port.GeneratedTurn;
import com.neowadaeum.play.port.GenerationContexts;
import com.neowadaeum.play.port.ProviderCallFailedException;
import com.neowadaeum.play.port.SummaryRequest;
import com.neowadaeum.play.port.TurnRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Gemini REST 와이어 계약. 실제 AI는 호출하지 않는다 (B-22-1). */
class GeminiStoryProviderContractTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String TURN_PATH = "/v1beta/models/gemini-turn:generateContent";
	private static final String VALID_TURN = """
			{"paragraphs":[{"type":"narration","text":"문이 열렸다."}],
			 "choices":[{"order":1,"text":"들어간다"}]}
			""";

	private final List<AiCallLog.Draft> recorded = Collections.synchronizedList(new ArrayList<>());
	private WireMockServer server;
	private GeminiStoryProvider provider;

	@BeforeEach
	void startServer() {
		this.server = new WireMockServer(WireMockConfiguration.options().dynamicPort().http2PlainDisabled(true));
		this.server.start();
		this.provider = providerWith(Map.of());
	}

	@AfterEach
	void stopServer() {
		this.server.stop();
	}

	private GeminiStoryProvider providerWith(Map<String, GeminiProperties.ModelPrice> pricing) {
		GeminiProperties properties = new GeminiProperties("test-key",
				new GeminiProperties.Models("gemini-turn", "gemini-summary", "gemini-safety", "gemini-outline"),
				"http://localhost:" + this.server.port(), 4096, pricing);
		return new GeminiStoryProvider(
				GeminiProviderConfiguration.restClient(properties, new ProviderProperties(null, null)), properties,
				new TurnPromptFactory(new PromptAssembler(new FixedTokenCounter(), RecentTurnsProperties.defaults())),
				new TurnOutputParser(), this.recorded::add);
	}

	private static TurnRequest request() {
		return TurnRequest.opening(UUID.fromString("11111111-1111-4111-8111-111111111111"),
				GenerationContexts.populated());
	}

	private void respond(String path, String text) {
		respond(path, text, null);
	}

	private void respond(String path, String text, String usage) {
		String metadata = usage == null ? "" : ",\"usageMetadata\":" + usage;
		this.server.stubFor(post(urlEqualTo(path)).willReturn(aResponse().withStatus(200)
				.withHeader("content-type", "application/json")
				.withBody("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":%s}]}}]%s}"
						.formatted(JSON.writeValueAsString(text), metadata))));
	}

	@Test
	void B22_1_a_valid_response_becomes_a_generated_turn() {
		respond(TURN_PATH, VALID_TURN);
		GeneratedTurn turn = this.provider.generateTurn(request());
		assertThat(turn.paragraphs()).singleElement().satisfies(p -> assertThat(p.text()).isEqualTo("문이 열렸다."));
		assertThat(turn.choices()).singleElement().satisfies(c -> assertThat(c.text()).isEqualTo("들어간다"));
	}

	@Test
	void I3_I7_the_key_is_a_header_and_system_instruction_is_separate() {
		respond(TURN_PATH, VALID_TURN);
		this.provider.generateTurn(request());

		this.server.verify(postRequestedFor(urlEqualTo(TURN_PATH)).withHeader("x-goog-api-key", equalTo("test-key")));
		JsonNode body = sentBody();
		assertThat(body.path("systemInstruction").path("parts").path(0).path("text").asString())
				.contains("15세 이용가");
		assertThat(body.path("contents").path(0).path("parts").path(0).path("text").asString())
				.doesNotContain("15세 이용가");
		assertThat(body.toString()).doesNotContain("test-key");
	}

	@Test
	void R3_3_structured_output_uses_the_json_schema_field() {
		respond(TURN_PATH, VALID_TURN);
		this.provider.generateTurn(request());

		JsonNode config = sentBody().path("generationConfig");
		assertThat(config.path("responseMimeType").asString()).isEqualTo("application/json");
		assertThat(config.path("responseJsonSchema").path("properties").path("paragraphs").path("type").asString())
				.isEqualTo("array");
		assertThat(config.has("responseSchema")).isFalse();
		assertThat(this.provider.capabilities().structuredOutput()).isTrue();
		assertThat(this.provider.capabilities().supportsSystemRole()).isTrue();
		assertThat(this.provider.providerId()).isEqualTo("gemini");
	}

	@Test
	void R5_8_a_provider_block_without_a_candidate_is_a_schema_violation() {
		this.server.stubFor(post(urlEqualTo(TURN_PATH)).willReturn(aResponse().withStatus(200)
				.withHeader("content-type", "application/json")
				.withBody("{\"candidates\":[],\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}")));
		assertThatThrownBy(() -> this.provider.generateTurn(request()))
				.isInstanceOf(TurnOutputSchemaException.class);
		assertThat(this.recorded).singleElement().satisfies(draft -> assertThat(draft.responseRaw()).contains("candidates"));
	}

	@Test
	void SEC3_an_http_failure_exposes_neither_key_nor_response_body() {
		this.server.stubFor(post(urlEqualTo(TURN_PATH)).willReturn(aResponse().withStatus(401)
				.withBody("{\"error\":\"invalid test-key\"}")));
		assertThatThrownBy(() -> this.provider.generateTurn(request()))
				.isInstanceOf(ProviderCallFailedException.class)
				.hasMessageNotContaining("test-key")
				.hasMessageNotContaining("invalid");
		assertThat(this.recorded).singleElement().satisfies(draft -> assertThat(draft.responseRaw()).isNull());
	}

	@Test
	void R3_7_a_server_error_is_reported_as_a_provider_call_failure() {
		this.server.stubFor(post(urlEqualTo(TURN_PATH)).willReturn(aResponse().withStatus(503)));
		assertThatThrownBy(() -> this.provider.generateTurn(request()))
				.isInstanceOf(ProviderCallFailedException.class);
	}

	@Test
	void R3_6_each_operation_calls_its_own_model() {
		respond("/v1beta/models/gemini-summary:generateContent", "압축된 줄거리");
		respond("/v1beta/models/gemini-safety:generateContent", "{\"categories\":[\"rating_exceeded\"]}");
		respond("/v1beta/models/gemini-outline:generateContent",
				"""
						{"chapters":[{"title":"시작","summary":"문이 열린다"}],
						 "endings":[{"label":"귀환","epilogue":"돌아온다"}]}
						""");

		assertThat(this.provider.summarize(new SummaryRequest(null,
				List.of(new SummaryRequest.TurnDigest(1, null, "문이 열림")), 600))).isEqualTo("압축된 줄거리");
		assertThat(this.provider.classifySafety(new SafetyClassificationRequest(List.of("검수 대상"))))
				.containsExactly(SafetyCategory.RATING_EXCEEDED);
		assertThat(this.provider.draftOutline(new OutlineRequest("봄의 학교", 5, 3)).chapters())
				.singleElement().satisfies(chapter -> assertThat(chapter.title()).isEqualTo("시작"));
		assertThat(this.recorded).extracting(AiCallLog.Draft::modelId)
				.containsExactly("gemini-summary", "gemini-safety", "gemini-outline");
		assertThat(this.recorded.get(1).safetyFlags()).isEqualTo("rating_exceeded");
	}

	@Test
	void S13_53_usage_is_recorded_and_priced_only_from_configuration() {
		respond(TURN_PATH, VALID_TURN, "{\"promptTokenCount\":10,\"candidatesTokenCount\":20}");
		this.provider = providerWith(Map.of("gemini-turn",
				new GeminiProperties.ModelPrice(new BigDecimal("1000"), new BigDecimal("2000"))));

		this.provider.generateTurn(request());

		assertThat(this.recorded).singleElement().satisfies(draft -> {
			assertThat(draft.inputTokens()).isEqualTo(10);
			assertThat(draft.outputTokens()).isEqualTo(20);
			assertThat(draft.costMicroKrw()).isEqualTo(50_000L);
		});
	}

	private JsonNode sentBody() {
		return JSON.readTree(this.server.getAllServeEvents().getLast().getRequest().getBodyAsString());
	}
}

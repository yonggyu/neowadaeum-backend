package com.neowadaeum.ai.provider.anthropic;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.neowadaeum.ai.prompt.PromptAssembler;
import com.neowadaeum.common.support.RecentTurnsProperties;
import com.neowadaeum.ai.prompt.TurnPromptFactory;
import com.neowadaeum.ai.provider.ProviderProperties;
import com.neowadaeum.ai.schema.TurnOutputParser;
import com.neowadaeum.ai.schema.TurnOutputSchemaException;
import com.neowadaeum.common.support.FixedTokenCounter;
import com.neowadaeum.common.support.RecentTurnsProperties;
import com.neowadaeum.play.port.GeneratedTurn;
import com.neowadaeum.play.port.GenerationContexts;
import com.neowadaeum.play.port.ProviderCallFailedException;
import com.neowadaeum.play.port.ParagraphType;
import com.neowadaeum.play.port.TurnRequest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Anthropic 어댑터 계약 테스트 (B-22, {@code .claude/rules/testing.md}).
 *
 * <p><b>실제 AI 를 부르지 않는다.</b> 고정 응답 서버가 와이어 계약을 대신한다 — 무엇을 보내는지,
 * 무엇을 읽는지, 실패를 어떻게 나누는지가 검증 대상이다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001). WireMock 은 프로세스 안에서 뜬다.
 */
class AnthropicStoryProviderContractTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final UUID STORY_VERSION = UUID.fromString("11111111-1111-4111-8111-111111111111");

	/** §5.2 를 만족하는 응답 본문. 구조화 출력이라도 텍스트 블록에 담겨 온다. */
	private static final String VALID_TURN = """
			{"speakerName":"유나",
			 "paragraphs":[{"type":"narration","text":"복도 끝에서 발소리가 멈췄다."},
			               {"type":"dialogue","text":"거기 서 있으면 문 못 열어."}],
			 "choices":[{"order":1,"text":"비켜 준다"},{"order":2,"text":"먼저 말을 건다"}],
			 "stateChanges":{"affinity.yuna":2},
			 "chapterAdvanceSuggested":false,
			 "endingSuggested":null}
			""";

	private WireMockServer server;

	private AnthropicStoryProvider provider;

	/**
	 * <b>평문 HTTP/2 를 끈다.</b> JDK 클라이언트가 h2c 로 협상하면 이 서버가
	 * {@code RST_STREAM} 으로 끊는다 — <b>테스트 서버의 제약이지 어댑터의 문제가 아니다.</b>
	 * 운영 프로토콜을 테스트 사정에 맞춰 낮추지 않고 여기서 닫는다.
	 */
	@BeforeEach
	void startServer() {
		this.server = new WireMockServer(WireMockConfiguration.options().dynamicPort().http2PlainDisabled(true));
		this.server.start();

		AnthropicProperties properties = new AnthropicProperties("test-key", turnModel("claude-opus-5"),
				"http://localhost:" + this.server.port(), 4096);

		this.provider = new AnthropicStoryProvider(
				RestClient.builder()
						.baseUrl(properties.baseUrl())
						.defaultHeader("x-api-key", properties.apiKey())
						.defaultHeader("anthropic-version", "2023-06-01")
						.build(),
				properties,
				new TurnPromptFactory(new PromptAssembler(new FixedTokenCounter(), RecentTurnsProperties.defaults())),
				new TurnOutputParser());
	}

	@AfterEach
	void stopServer() {
		this.server.stop();
	}

	private static TurnRequest request() {
		return TurnRequest.opening(STORY_VERSION, GenerationContexts.populated());
	}

	private void respondWith(String responseText) {
		this.server.stubFor(post(urlEqualTo("/v1/messages")).willReturn(aResponse()
				.withStatus(200)
				.withHeader("content-type", "application/json")
				.withBody("{\"content\":[{\"type\":\"text\",\"text\":%s}]}"
						.formatted(JSON.writeValueAsString(responseText)))));
	}

	/** 정상 응답이 {@code GeneratedTurn} 이 된다 — 어댑터가 파서를 거친다는 뜻이다 (B-21). */
	@Test
	void B22_a_valid_response_becomes_a_generated_turn() {
		respondWith(VALID_TURN);

		GeneratedTurn turn = this.provider.generateTurn(request());

		assertThat(turn.paragraphs()).hasSize(2);
		assertThat(turn.paragraphs().get(1).type()).isEqualTo(ParagraphType.DIALOGUE);
		assertThat(turn.paragraphs().get(1).speakerName())
				.as("턴 단위 화자가 대사 문단으로 복사되지 않았다 (#84)")
				.isEqualTo("유나");
		assertThat(turn.choices()).hasSize(2);
	}

	/** §3 — 요청 표면. 경로 · 인증 · API 버전이 계약이다. */
	@Test
	void S3_the_request_carries_the_documented_headers() {
		respondWith(VALID_TURN);

		this.provider.generateTurn(request());

		this.server.verify(postRequestedFor(urlEqualTo("/v1/messages"))
				.withHeader("x-api-key", equalTo("test-key"))
				.withHeader("anthropic-version", equalTo("2023-06-01")));
	}

	/**
	 * <b>형식을 프롬프트로만 부탁하지 않는다.</b> {@code output_config.format} 에 §5.2 스키마를
	 * 실어 API 가 강제하게 한다.
	 */
	@Test
	void R5_2_the_request_pins_the_output_schema() {
		respondWith(VALID_TURN);

		this.provider.generateTurn(request());

		JsonNode body = sentBody();
		assertThat(body.path("output_config").path("format").path("type").asString())
				.isEqualTo("json_schema");

		JsonNode schema = body.path("output_config").path("format").path("schema");
		assertThat(schema.path("properties").has("paragraphs")).isTrue();
		assertThat(schema.path("properties").path("paragraphs").path("items")
				.path("properties").path("type").path("enum").toString())
				.contains("dialogue", "narration");
	}

	/**
	 * <b>I-7 — {@code SYSTEM} 이 작품 입력과 같은 평면에 놓이지 않는다.</b>
	 *
	 * <p>사용자 메시지에 접어 넣으면 <b>"이전 지시를 무시하라"가 같은 자리에서 경쟁한다.</b>
	 * 별도 필드로 나가는 것이 그 경쟁을 없앤다.
	 */
	@Test
	void I7_the_system_layer_is_sent_as_its_own_field() {
		respondWith(VALID_TURN);

		this.provider.generateTurn(request());

		JsonNode body = sentBody();
		assertThat(body.path("system").asString()).contains("15세 이용가");
		assertThat(body.path("messages").get(0).path("content").asString())
				.as("SYSTEM 이 사용자 메시지에도 들어가면 분리한 의미가 없다")
				.doesNotContain("15세 이용가")
				.contains("[WORLD]", "[OUTPUT_SPEC]");
	}

	/** 모델과 응답 상한이 설정에서 온다 (R3.1). */
	@Test
	void R3_1_model_and_max_tokens_come_from_configuration() {
		respondWith(VALID_TURN);

		this.provider.generateTurn(request());

		assertThat(sentBody().path("model").asString()).isEqualTo("claude-opus-5");
		assertThat(sentBody().path("max_tokens").asInt()).isEqualTo(4096);
	}

	/**
	 * <b>R3.6 — 턴 생성은 {@code turn} 용 모델로 나간다.</b>
	 *
	 * <p>네 용도에 서로 다른 값을 넣고 <b>실제로 나가는 것</b>을 본다. 설정이 나뉘어 있다는 확인만으로는
	 * 부족하다 — 어댑터가 엉뚱한 용도를 고르면 <b>요약용 저비용 모델이 본문을 쓰게 되고</b>, 그 증상은
	 * 에러가 아니라 이야기 품질로 나타난다.
	 */
	@Test
	void R3_6_the_turn_call_uses_the_turn_model_not_another_purpose() {
		AnthropicProperties perPurpose = new AnthropicProperties("test-key",
				new AnthropicProperties.Models("claude-opus-5", "summary-model", "safety-model", "outline-model"),
				"http://localhost:" + this.server.port(), 4096);

		AnthropicStoryProvider provider = new AnthropicStoryProvider(
				AnthropicProviderConfiguration.restClient(perPurpose, new ProviderProperties(null, null)),
				perPurpose,
				new TurnPromptFactory(new PromptAssembler(new FixedTokenCounter(), RecentTurnsProperties.defaults())),
				new TurnOutputParser());

		respondWith(VALID_TURN);
		provider.generateTurn(request());

		assertThat(sentBody().path("model").asString())
				.as("턴 생성이 다른 용도의 모델로 나갔다 — 비용과 품질이 함께 어긋난다")
				.isEqualTo("claude-opus-5");
	}

	/**
	 * 스키마를 못 맞춘 응답은 <b>스키마 예외</b>다 — 재요청 경로가 이것을 받는다 (R5.8).
	 *
	 * <p>호출 실패로 뭉뚱그리면 재요청이 일어나지 않는다.
	 */
	@Test
	void R5_8_a_malformed_body_is_a_schema_violation_not_a_call_failure() {
		respondWith("{\"paragraphs\": \"통 문자열 본문\"}");

		assertThatThrownBy(() -> this.provider.generateTurn(request()))
				.isInstanceOf(TurnOutputSchemaException.class);
	}

	/** 텍스트 블록이 없는 응답도 스키마 위반이다 — 형태가 기대와 다르면 재요청해 볼 만하다. */
	@Test
	void R5_8_a_response_without_a_text_block_is_a_schema_violation() {
		this.server.stubFor(post(urlEqualTo("/v1/messages")).willReturn(aResponse()
				.withStatus(200).withHeader("content-type", "application/json")
				.withBody("{\"content\":[]}")));

		assertThatThrownBy(() -> this.provider.generateTurn(request()))
				.isInstanceOf(TurnOutputSchemaException.class);
	}

	/**
	 * <b>4xx · 5xx 는 재요청 대상이 아니다.</b> 같은 요청을 다시 보내 나아지지 않는다 —
	 * 인증 실패는 재시도해도 인증 실패다.
	 */
	@Test
	void B22_an_http_error_is_a_call_failure_not_a_schema_violation() {
		this.server.stubFor(post(urlEqualTo("/v1/messages"))
				.willReturn(aResponse().withStatus(401).withBody("{\"error\":{\"message\":\"invalid key\"}}")));

		assertThatThrownBy(() -> this.provider.generateTurn(request()))
				.isInstanceOf(ProviderCallFailedException.class);
	}

	/**
	 * S-3 — <b>예외에 API 키도 응답 본문도 없다.</b>
	 *
	 * <p>에러 응답이 키를 되비쳐 오는 경우가 있고, 예외는 로그로 흐른다. "있어야 할 것"만 단언하면
	 * 값이 새어도 통과한다 ({@code .claude/rules/testing.md}).
	 */
	@Test
	void S3_the_failure_exception_carries_neither_the_key_nor_the_body() {
		this.server.stubFor(post(urlEqualTo("/v1/messages")).willReturn(aResponse()
				.withStatus(401).withBody("{\"error\":{\"message\":\"invalid x-api-key: test-key\"}}")));

		assertThatThrownBy(() -> this.provider.generateTurn(request()))
				.hasMessageNotContaining("test-key")
				.hasMessageNotContaining("invalid x-api-key");
	}

	/**
	 * R5.8 · R3.3 — <b>구조화 출력을 지원한다고 보고한다.</b>
	 *
	 * <p>{@code false} 로 보고하면 재요청이 2회가 되고 그 여분은 비용만 쓴다.
	 */
	@Test
	void R3_3_the_adapter_reports_structured_output_support() {
		assertThat(this.provider.capabilities().structuredOutput()).isTrue();
		assertThat(this.provider.capabilities().supportsSystemRole()).isTrue();
		assertThat(this.provider.providerId()).isEqualTo(AnthropicStoryProvider.PROVIDER_ID);
	}

	/** §0.2 — 아직 구현하지 않은 용도는 예외 그대로다. 스텁으로 통과시키지 않는다. */
	@Test
	void S0_2_unimplemented_uses_stay_unimplemented() {
		assertThatThrownBy(() -> this.provider.summarize(null))
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> this.provider.draftOutline(null))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	private JsonNode sentBody() {
		return JSON.readTree(this.server.getAllServeEvents().getFirst().getRequest().getBodyAsString());
	}
	/** 턴 생성 모델만 채운 설정. 용도별 분리(B-24) 이후 대부분의 테스트가 필요로 하는 최소 형태다. */
	private static AnthropicProperties.Models turnModel(String model) {
		return new AnthropicProperties.Models(model, null, null, null);
	}

}

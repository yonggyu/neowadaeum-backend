package com.neowadaeum.ai.provider.anthropic;

import com.neowadaeum.ai.prompt.AssembledPrompt;
import com.neowadaeum.ai.prompt.PromptLayer;
import com.neowadaeum.ai.prompt.TurnPromptFactory;
import com.neowadaeum.ai.log.AiCallLog;
import com.neowadaeum.ai.log.AiCallRecorder;
import com.neowadaeum.ai.provider.AiCallAttempt;
import com.neowadaeum.ai.provider.AiCallFallback;
import com.neowadaeum.ai.provider.AiPurpose;
import com.neowadaeum.ai.provider.OutlineRequest;
import com.neowadaeum.ai.provider.OutlineResult;
import com.neowadaeum.ai.provider.ProviderCapabilities;
import com.neowadaeum.ai.provider.StoryProvider;
import com.neowadaeum.ai.provider.SummaryRequest;
import com.neowadaeum.ai.schema.TurnOutputParser;
import com.neowadaeum.ai.schema.TurnOutputSchemaException;
import com.neowadaeum.play.port.GeneratedTurn;
import com.neowadaeum.play.port.ProviderCallFailedException;
import com.neowadaeum.play.port.TurnRequest;
import java.util.stream.Collectors;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Anthropic Messages API 어댑터 (B-22, §3).
 *
 * <p><b>SDK 를 쓰지 않는다.</b> {@code .claude/rules/ai.md} 가 {@code RestClient} 직접 구현을
 * 요구하며 이유까지 적어 뒀다 — <b>와이어 페이로드를 서버가 소유해야 I-3·I-7 을 강제할 수
 * 있다.</b> SDK 가 본문을 조립하면 B-19 의 화이트리스트가 검사할 대상이 사라진다.
 *
 * <p><b>형식을 프롬프트로만 부탁하지 않는다.</b> {@code output_config.format} 에 §5.2 스키마를
 * 실어 <b>API 가 형식을 강제하게</b> 한다. {@code OUTPUT_SPEC} 레이어는 그대로 두는데, 그것이
 * 없어지면 스키마를 강제하지 못하는 Provider(B-23)에서 형식이 무너지고, 무엇보다 I-7 의 플랫폼
 * 레이어는 provider 사정과 무관하게 유지돼야 한다.
 *
 * <p><b>시간 제한은 여기 없다.</b> {@code TimeLimitedStoryProvider} 가 감싸며 (R6.4), 그 취소는
 * 인터럽트로 닿는다. 다만 <b>블로킹 소켓 읽기는 인터럽트에 항상 반응하지 않으므로</b> 클라이언트
 * 자체에도 읽기 상한을 둔다 — 그것이 없으면 취소된 호출의 스레드가 살아남는다.
 *
 * <p><b>실패를 둘로 나눈다.</b> 스키마 위반은 {@code TurnOutputSchemaException} 으로 나가 재요청
 * 경로를 타고 (R5.8), 연결·인증·4xx·5xx 는 {@link ProviderCallFailedException} 으로 나가 그대로
 * 502 가 된다. 뭉뚱그리면 <b>재시도해도 소용없는 실패에 비용을 한 번 더 쓴다.</b>
 *
 * <p><b>S-3 — 프롬프트 원문·응답 원문·API 키를 로그에 남기지 않는다.</b> 이 클래스에는 로거가
 * 없다. 원문 보관은 {@code ai_call_log}(B-11 · B-25)의 일이며 아직 그 자리가 없다.
 */
public class AnthropicStoryProvider implements StoryProvider {

	public static final String PROVIDER_ID = "anthropic";

	/** §5.2 를 API 가 강제하게 만드는 스키마. {@code OUTPUT_SPEC} 문구와 같은 것을 가리킨다. */
	private static final String TURN_OUTPUT_SCHEMA = """
			{
			  "type": "object",
			  "properties": {
			    "speakerName": {"type": ["string", "null"]},
			    "paragraphs": {
			      "type": "array",
			      "items": {
			        "type": "object",
			        "properties": {
			          "type": {"type": "string", "enum": ["dialogue", "narration"]},
			          "text": {"type": "string"}
			        },
			        "required": ["type", "text"],
			        "additionalProperties": false
			      }
			    },
			    "choices": {
			      "type": "array",
			      "items": {
			        "type": "object",
			        "properties": {
			          "order": {"type": "integer"},
			          "text": {"type": "string"}
			        },
			        "required": ["order", "text"],
			        "additionalProperties": false
			      }
			    },
			    "stateChanges": {"type": "object"},
			    "chapterAdvanceSuggested": {"type": "boolean"},
			    "endingSuggested": {"type": ["string", "null"]}
			  },
			  "required": ["paragraphs", "choices"],
			  "additionalProperties": false
			}
			""";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final RestClient restClient;

	private final AnthropicProperties properties;

	private final TurnPromptFactory prompts;

	private final TurnOutputParser parser;

	private final AiCallRecorder recorder;

	public AnthropicStoryProvider(RestClient restClient, AnthropicProperties properties,
			TurnPromptFactory prompts, TurnOutputParser parser, AiCallRecorder recorder) {
		this.restClient = restClient;
		this.properties = properties;
		this.prompts = prompts;
		this.parser = parser;
		this.recorder = recorder;
	}

	@Override
	public String providerId() {
		return PROVIDER_ID;
	}

	/**
	 * <b>{@code structuredOutput = true}</b> — 스키마를 API 가 강제하므로 재요청은 1회다 (R5.8).
	 * {@code false} 로 보고하면 {@code SchemaRetryingStoryProvider} 가 2회를 주고, 그 여분은
	 * <b>비용만 쓴다</b> (R3.3).
	 *
	 * <p>{@code maxContextTokens} 는 프롬프트 예산이 맞춰야 할 상한이다 (§4.3). {@code SYSTEM}
	 * 역할을 별도 필드로 받으므로 {@code supportsSystemRole = true} 다.
	 */
	@Override
	public ProviderCapabilities capabilities() {
		return new ProviderCapabilities(true, 200_000, true);
	}

	/**
	 * <b>기록은 성공과 실패 양쪽에서 남는다</b> (B-25). 실패한 호출도 요청은 남는다 —
	 * <b>무엇을 보냈는지가 단서다.</b>
	 *
	 * <p>기록이 파싱보다 먼저인 것도 의도다. 스키마 위반으로 재요청이 돌면 그 시도의 응답 원문이
	 * 남아야 <b>모델이 무엇을 돌려줬길래 거부됐는지</b>를 볼 수 있다.
	 */
	@Override
	public GeneratedTurn generateTurn(TurnRequest request) {
		AssembledPrompt prompt = this.prompts.create(request);
		ObjectNode body = body(prompt);
		long startedAt = System.nanoTime();

		JsonNode response;
		try {
			response = this.restClient.post()
					.uri("/v1/messages")
					.body(body)
					.retrieve()
					.body(JsonNode.class);
		}
		catch (RestClientException ex) {
			record(request, body, null, startedAt);
			// 원문을 메시지에 넣지 않는다 (S-3). RestClient 의 예외는 본문 일부를 담을 수 있어
			// 원인으로도 붙이지 않는다 — 예외는 로그로 흐른다.
			throw new ProviderCallFailedException("anthropic call failed");
		}

		record(request, body, response, startedAt);
		return this.parser.parse(text(response)).toGeneratedTurn();
	}

	/**
	 * 호출 한 건을 기록한다 (S-3 — 원문 보관처는 {@code ai_call_log} 하나다).
	 *
	 * <p><b>{@code cost_micro} 를 채우지 않는다.</b> 단가는 벤더·모델별로 다르고 시간에 따라
	 * 바뀐다 — 코드에 박으면 <b>틀린 순간부터 조용히 틀린 값이 쌓이고</b>, 그 값으로 예산을
	 * 판단하게 된다. 단가표가 생기는 시점에 채운다.
	 *
	 * <p><b>{@code playerRef} 를 담을 자리가 없다</b> (I-3). {@link AiCallLog.Draft} 에 그
	 * 컴포넌트가 없으므로 실을 방법 자체가 없다.
	 */
	private void record(TurnRequest request, ObjectNode body, JsonNode response, long startedAt) {
		this.recorder.record(new AiCallLog.Draft(
				null,
				null,
				AiPurpose.TURN.wireValue(),
				PROVIDER_ID,
				body.path("model").asString(""),
				AiCallFallback.intendedProviderId(),
				body.toString(),
				(response != null) ? response.toString() : null,
				(response != null) ? intOrNull(response.path("usage").path("input_tokens")) : null,
				(response != null) ? intOrNull(response.path("usage").path("output_tokens")) : null,
				(int) java.time.Duration.ofNanos(System.nanoTime() - startedAt).toMillis(),
				null,
				null,
				AiCallAttempt.current()));
	}

	private static Integer intOrNull(JsonNode node) {
		return node.isIntegralNumber() ? node.intValue() : null;
	}

	/**
	 * 와이어 본문 (§5.2, Messages API).
	 *
	 * <p><b>{@code SYSTEM} 레이어를 {@code system} 필드로 분리한다.</b> 사용자 메시지에 접어 넣으면
	 * 작품 입력과 같은 자리에 놓이고, <b>"이전 지시를 무시하라"가 같은 평면에서 경쟁한다</b> (I-7).
	 * 나머지 레이어는 조립된 순서 그대로 하나의 사용자 메시지가 된다.
	 */
	/**
	 * 용도에 맞는 모델을 고른다 (R3.6, B-24).
	 *
	 * <p><b>없으면 실패한다.</b> 조용히 턴 생성 모델을 빌려 쓰지 않는다 — 그 사고는 에러가 아니라
	 * <b>비용 청구서</b>로 나타나고, 그때는 이미 한 달치다.
	 */
	private String modelFor(AiPurpose purpose) {
		String model = this.properties.modelFor(purpose);
		if (model == null) {
			throw new ProviderCallFailedException(
					"no anthropic model configured for purpose " + purpose.wireValue());
		}
		return model;
	}

	private ObjectNode body(AssembledPrompt prompt) {
		ObjectNode body = JSON.createObjectNode();
		body.put("model", modelFor(AiPurpose.TURN));
		body.put("max_tokens", this.properties.maxTokens());
		body.put("system", layer(prompt, PromptLayer.SYSTEM));

		ObjectNode message = body.putArray("messages").addObject();
		message.put("role", "user");
		message.put("content", withoutSystem(prompt));

		body.set("output_config", JSON.createObjectNode()
				.set("format", JSON.createObjectNode()
						.put("type", "json_schema")
						.set("schema", JSON.readTree(TURN_OUTPUT_SCHEMA))));
		return body;
	}

	private static String layer(AssembledPrompt prompt, PromptLayer wanted) {
		return prompt.sections().stream()
				.filter(section -> section.layer() == wanted)
				.map(AssembledPrompt.Section::text)
				.findFirst()
				.orElse("");
	}

	private static String withoutSystem(AssembledPrompt prompt) {
		return prompt.sections().stream()
				.filter(section -> section.layer() != PromptLayer.SYSTEM)
				.map(section -> "[" + section.layer().name() + "]\n" + section.text())
				.collect(Collectors.joining("\n\n"));
	}

	/**
	 * 응답에서 본문 텍스트를 꺼낸다.
	 *
	 * <p><b>{@code content} 는 블록 배열이다.</b> 구조화 출력이라도 텍스트 블록에 담겨 오며,
	 * 사고 블록 같은 다른 종류가 섞일 수 있으므로 {@code type == "text"} 만 모은다. 형태가
	 * 기대와 다르면 스키마 위반으로 넘긴다 — 재요청 경로가 그것을 받는다 (R5.8).
	 */
	private static String text(JsonNode response) {
		if (response == null || !response.path("content").isArray()) {
			throw new TurnOutputSchemaException("anthropic response has no content array");
		}

		StringBuilder text = new StringBuilder();
		for (JsonNode block : response.path("content")) {
			if ("text".equals(block.path("type").asString(null))) {
				text.append(block.path("text").asString(""));
			}
		}
		if (text.isEmpty()) {
			throw new TurnOutputSchemaException("anthropic response has no text block");
		}
		return text.toString();
	}

	/** <b>요약은 B-34 다.</b> 스텁으로 통과시키지 않는다 (§0.2). */
	@Override
	public String summarize(SummaryRequest request) {
		throw new UnsupportedOperationException("summarize is B-34");
	}

	/** <b>아웃라인 초안은 B-52 다.</b> 위와 같은 이유다. */
	@Override
	public OutlineResult draftOutline(OutlineRequest request) {
		throw new UnsupportedOperationException("draftOutline is B-52");
	}

}

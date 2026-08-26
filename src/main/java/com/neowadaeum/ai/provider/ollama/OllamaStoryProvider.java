package com.neowadaeum.ai.provider.ollama;

import com.neowadaeum.ai.log.AiCallLog;
import com.neowadaeum.ai.log.AiCallRecorder;
import com.neowadaeum.ai.prompt.AssembledPrompt;
import com.neowadaeum.ai.prompt.PromptLayer;
import com.neowadaeum.ai.prompt.TurnPromptFactory;
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
import java.time.Duration;
import java.util.stream.Collectors;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Ollama 어댑터 (B-23, R3.2).
 *
 * <p><b>{@code structuredOutput = false} 다.</b> Ollama 의 {@code format} 옵션은 벤더마다 지원이
 * 갈리고 모델에 따라 무시된다 — <b>"강제된다"고 보고할 수 없다.</b> 그 보고 하나가 재요청 횟수를
 * 바꾸며(R3.3 — 2회), 그것이 이 어댑터에서 가장 중요한 한 줄이다.
 *
 * <p><b>I-13 — 이 어댑터의 응답도 Safety L2 를 거친다.</b> 무검열 로컬 모델을 붙여도 15세 등급이
 * 유지되는 것은 <b>판정이 provider 밖에 있기 때문</b>이다 (R3.4). 이 클래스는 검수를 건너뛸 수단을
 * 갖지 않는다.
 *
 * <p><b>{@code OUTPUT SPEC} 이 여기서 값을 한다.</b> 스키마를 API 가 강제해 주지 않으므로 형식은
 * 프롬프트로만 부탁할 수 있다 — B-20 이 그 레이어를 플랫폼 소유로 못박아 둔 이유다 (I-7).
 */
public class OllamaStoryProvider implements StoryProvider {

	public static final String PROVIDER_ID = "ollama";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final RestClient restClient;

	private final OllamaProperties properties;

	private final TurnPromptFactory prompts;

	private final TurnOutputParser parser;

	private final AiCallRecorder recorder;

	public OllamaStoryProvider(RestClient restClient, OllamaProperties properties, TurnPromptFactory prompts,
			TurnOutputParser parser, AiCallRecorder recorder) {
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
	 * <b>{@code structuredOutput = false}</b> — 그래서 재요청이 2회다 (R3.3).
	 *
	 * <p>{@code true} 로 보고하면 1회만 주고, 형식을 못 맞춘 로컬 모델이 <b>한 번 더 기회를 받지
	 * 못한다.</b> {@code maxContextTokens} 는 모델마다 다르므로 보수적으로 잡는다 — 넘치면
	 * 조립기가 먼저 실패한다 (§4.4).
	 */
	@Override
	public ProviderCapabilities capabilities() {
		return new ProviderCapabilities(false, 8_192, true);
	}

	@Override
	public GeneratedTurn generateTurn(TurnRequest request) {
		AssembledPrompt prompt = this.prompts.create(request);
		ObjectNode body = body(prompt);
		long startedAt = System.nanoTime();

		JsonNode response;
		try {
			response = this.restClient.post().uri("/api/chat").body(body).retrieve().body(JsonNode.class);
		}
		catch (RestClientException ex) {
			record(body, null, startedAt);
			throw new ProviderCallFailedException("ollama call failed");
		}

		record(body, response, startedAt);
		return this.parser.parse(text(response)).toGeneratedTurn();
	}

	/**
	 * 와이어 본문 (Ollama {@code /api/chat}).
	 *
	 * <p><b>{@code SYSTEM} 을 별도 메시지로 보낸다</b> (I-7). Anthropic 이 {@code system} 필드를
	 * 쓰는 것과 같은 이유이며, 형식만 다르다 — 작품 입력과 같은 평면에 두지 않는다.
	 *
	 * <p><b>{@code stream: false}</b> — 스트리밍을 쓰지 않는다. 지금 응답은 통째로 파싱되고
	 * (B-21), 스트리밍 도입 판단은 B-46 의 실측 뒤다.
	 */
	private ObjectNode body(AssembledPrompt prompt) {
		ObjectNode body = JSON.createObjectNode();
		body.put("model", modelFor(AiPurpose.TURN));
		body.put("stream", false);

		var messages = body.putArray("messages");
		ObjectNode system = messages.addObject();
		system.put("role", "system");
		system.put("content", layer(prompt, PromptLayer.SYSTEM));

		ObjectNode user = messages.addObject();
		user.put("role", "user");
		user.put("content", withoutSystem(prompt));
		return body;
	}

	private String modelFor(AiPurpose purpose) {
		String model = this.properties.modelFor(purpose);
		if (model == null) {
			throw new ProviderCallFailedException("no ollama model configured for purpose " + purpose.wireValue());
		}
		return model;
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

	/** 응답 본문은 {@code message.content} 하나다. 형태가 다르면 스키마 위반으로 넘긴다 (R5.8). */
	private static String text(JsonNode response) {
		String content = (response != null) ? response.path("message").path("content").asString(null) : null;
		if (content == null || content.isBlank()) {
			throw new TurnOutputSchemaException("ollama response has no message content");
		}
		return content;
	}

	/** B-25 와 같은 형태로 남긴다. {@code cost_micro} 는 로컬 실행이라 <b>개념 자체가 없다.</b> */
	private void record(ObjectNode body, JsonNode response, long startedAt) {
		this.recorder.record(new AiCallLog.Draft(
				null, null, AiPurpose.TURN.wireValue(), PROVIDER_ID, body.path("model").asString(""),
				AiCallFallback.intendedProviderId(), body.toString(),
				(response != null) ? response.toString() : null,
				null, null,
				(int) Duration.ofNanos(System.nanoTime() - startedAt).toMillis(),
				null, null, AiCallAttempt.current()));
	}

	@Override
	public String summarize(SummaryRequest request) {
		throw new UnsupportedOperationException("summarize is B-34");
	}

	@Override
	public OutlineResult draftOutline(OutlineRequest request) {
		throw new UnsupportedOperationException("draftOutline is B-52");
	}
}

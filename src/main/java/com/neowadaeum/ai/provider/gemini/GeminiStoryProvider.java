package com.neowadaeum.ai.provider.gemini;

import com.neowadaeum.ai.log.AiCallLog;
import com.neowadaeum.ai.log.AiCallRecorder;
import com.neowadaeum.ai.prompt.AssembledPrompt;
import com.neowadaeum.ai.prompt.PlatformPrompts;
import com.neowadaeum.ai.prompt.PromptLayer;
import com.neowadaeum.ai.prompt.TurnPromptFactory;
import com.neowadaeum.ai.provider.AiCallAttempt;
import com.neowadaeum.ai.provider.AiCallFallback;
import com.neowadaeum.ai.provider.AiPurpose;
import com.neowadaeum.ai.provider.OutlineOutputFormat;
import com.neowadaeum.ai.provider.OutlinePrompt;
import com.neowadaeum.ai.provider.OutlineRequest;
import com.neowadaeum.ai.provider.OutlineResult;
import com.neowadaeum.ai.provider.ProviderCapabilities;
import com.neowadaeum.ai.provider.SafetyClassificationFormat;
import com.neowadaeum.ai.provider.StoryProvider;
import com.neowadaeum.ai.provider.SummaryPrompt;
import com.neowadaeum.ai.schema.OutlineOutputSchemaException;
import com.neowadaeum.ai.schema.TurnOutputParser;
import com.neowadaeum.ai.schema.TurnOutputSchemaException;
import com.neowadaeum.common.spi.SafetyCategory;
import com.neowadaeum.common.spi.SafetyClassificationFailedException;
import com.neowadaeum.common.spi.SafetyClassificationRequest;
import com.neowadaeum.play.port.GeneratedTurn;
import com.neowadaeum.play.port.ProviderCallFailedException;
import com.neowadaeum.play.port.SummaryRequest;
import com.neowadaeum.play.port.TurnRequest;
import java.time.Duration;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Gemini {@code generateContent} 어댑터 (B-22-1).
 *
 * <p>SDK 없이 와이어 페이로드를 직접 소유하고, 플랫폼 지시는 {@code systemInstruction}으로
 * 분리한다 (I-3, I-7). 시간 제한·재요청·fallback은 기존 Gateway 데코레이터가 담당한다.
 */
public class GeminiStoryProvider implements StoryProvider {

	public static final String PROVIDER_ID = "gemini";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final JsonNode TURN_SCHEMA = JSON.readTree("""
			{"type":"object","properties":{"speakerName":{"type":["string","null"]},
			"paragraphs":{"type":"array","items":{"type":"object","properties":{
			"type":{"type":"string","enum":["dialogue","narration"]},
			"text":{"type":"string"}},"required":["type","text"]}},
			"choices":{"type":"array","items":{"type":"object","properties":{
			"order":{"type":"integer"},"text":{"type":"string"}},"required":["order","text"]}},
			"stateChanges":{"type":"object"},"chapterAdvanceSuggested":{"type":"boolean"},
			"endingSuggested":{"type":["string","null"]}},"required":["paragraphs","choices"]}
			""");

	private static final JsonNode CLASSIFICATION_SCHEMA = JSON.readTree("""
			{"type":"object","properties":{"categories":{"type":"array","items":{"type":"string"}}},
			"required":["categories"]}
			""");

	private static final JsonNode OUTLINE_SCHEMA = JSON.readTree("""
			{"type":"object","properties":{"chapters":{"type":"array","items":{"type":"object",
			"properties":{"title":{"type":"string"},"summary":{"type":"string"}},"required":["title","summary"]}},
			"endings":{"type":"array","items":{"type":"object","properties":{"label":{"type":"string"},
			"epilogue":{"type":"string"}},"required":["label","epilogue"]}}},"required":["chapters","endings"]}
			""");

	private final RestClient restClient;
	private final GeminiProperties properties;
	private final TurnPromptFactory prompts;
	private final TurnOutputParser parser;
	private final AiCallRecorder recorder;

	public GeminiStoryProvider(RestClient restClient, GeminiProperties properties, TurnPromptFactory prompts,
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

	@Override
	public ProviderCapabilities capabilities() {
		return new ProviderCapabilities(true, 32_768, true);
	}

	@Override
	public GeneratedTurn generateTurn(TurnRequest request) {
		AssembledPrompt prompt = this.prompts.create(request);
		String system = layer(prompt, PromptLayer.SYSTEM);
		return invoke(AiPurpose.TURN, request(system, withoutSystem(prompt), this.properties.maxTokens(), TURN_SCHEMA),
				response -> this.parser.parse(text(response, () ->
						new TurnOutputSchemaException("gemini response has no text part"))).toGeneratedTurn(), value -> null);
	}

	@Override
	public Set<SafetyCategory> classifySafety(SafetyClassificationRequest request) {
		return invoke(AiPurpose.SAFETY,
				request(PlatformPrompts.SAFETY_JUDGE, String.join("\n", request.texts()), 256,
						CLASSIFICATION_SCHEMA),
				response -> SafetyClassificationFormat.parse(text(response, () ->
						new SafetyClassificationFailedException("gemini classification response has no text part"))),
				SafetyClassificationFormat::flags);
	}

	@Override
	public String summarize(SummaryRequest request) {
		return invoke(AiPurpose.SUMMARY,
				request(PlatformPrompts.SUMMARY, SummaryPrompt.compose(request), request.maxTokens(), null),
				response -> {
					String summary = text(response,
							() -> new ProviderCallFailedException("gemini summary response has no text part")).strip();
					if (summary.isBlank()) {
						throw new ProviderCallFailedException("gemini summary response is blank");
					}
					return summary;
				}, value -> null);
	}

	@Override
	public OutlineResult draftOutline(OutlineRequest request) {
		return invoke(AiPurpose.OUTLINE,
				request(PlatformPrompts.OUTLINE, OutlinePrompt.compose(request), this.properties.maxTokens(),
						OUTLINE_SCHEMA),
				response -> OutlineOutputFormat.parse(text(response,
						() -> new OutlineOutputSchemaException("gemini outline response has no text part")), request),
				value -> null);
	}

	private <T> T invoke(AiPurpose purpose, ObjectNode body, Function<JsonNode, T> decoder,
			Function<T, String> safetyFlags) {
		String model = modelFor(purpose);
		long startedAt = System.nanoTime();
		JsonNode response;
		try {
			response = this.restClient.post()
					.uri("/v1beta/models/{model}:generateContent", model)
					.body(body)
					.retrieve()
					.body(JsonNode.class);
		}
		catch (RestClientException ex) {
			record(purpose, model, body, null, startedAt, null);
			throw new ProviderCallFailedException("gemini " + purpose.wireValue() + " call failed",
					ProviderCallFailedException.typeChainOf(ex));
		}

		try {
			T result = decoder.apply(response);
			record(purpose, model, body, response, startedAt, safetyFlags.apply(result));
			return result;
		}
		catch (RuntimeException ex) {
			record(purpose, model, body, response, startedAt, null);
			throw ex;
		}
	}

	private ObjectNode request(String system, String user, int maxTokens, JsonNode schema) {
		ObjectNode body = JSON.createObjectNode();
		body.putObject("systemInstruction").putArray("parts").addObject().put("text", system);
		ObjectNode content = body.putArray("contents").addObject();
		content.put("role", "user");
		content.putArray("parts").addObject().put("text", user);
		ObjectNode generation = body.putObject("generationConfig").put("maxOutputTokens", maxTokens);
		if (schema != null) {
			generation.put("responseMimeType", "application/json").set("responseJsonSchema", schema);
		}
		return body;
	}

	private String modelFor(AiPurpose purpose) {
		String model = this.properties.modelFor(purpose);
		if (model == null) {
			throw new ProviderCallFailedException("no gemini model configured for purpose " + purpose.wireValue());
		}
		return model;
	}

	private void record(AiPurpose purpose, String model, ObjectNode body, JsonNode response, long startedAt,
			String safetyFlags) {
		Integer input = response == null ? null : intOrNull(response.path("usageMetadata").path("promptTokenCount"));
		Integer output = response == null ? null : intOrNull(response.path("usageMetadata").path("candidatesTokenCount"));
		this.recorder.record(new AiCallLog.Draft(null, null, purpose.wireValue(), PROVIDER_ID, model,
				AiCallFallback.intendedProviderId(), body.toString(), response == null ? null : response.toString(),
				input, output, (int) Duration.ofNanos(System.nanoTime() - startedAt).toMillis(),
				this.properties.costMicroKrw(model, input, output), safetyFlags, AiCallAttempt.current()));
	}

	private static Integer intOrNull(JsonNode node) {
		return node.isIntegralNumber() ? node.intValue() : null;
	}

	private static String text(JsonNode response, Supplier<? extends RuntimeException> missing) {
		JsonNode parts = response == null ? null : response.path("candidates").path(0).path("content").path("parts");
		if (parts == null || !parts.isArray()) {
			throw missing.get();
		}
		StringBuilder text = new StringBuilder();
		for (JsonNode part : parts) {
			if (part.path("text").isString()) {
				text.append(part.path("text").asString());
			}
		}
		if (text.isEmpty()) {
			throw missing.get();
		}
		return text.toString();
	}

	private static String layer(AssembledPrompt prompt, PromptLayer wanted) {
		return prompt.sections().stream().filter(section -> section.layer() == wanted)
				.map(AssembledPrompt.Section::text).findFirst().orElse("");
	}

	private static String withoutSystem(AssembledPrompt prompt) {
		return prompt.sections().stream().filter(section -> section.layer() != PromptLayer.SYSTEM)
				.map(section -> "[" + section.layer().name() + "]\n" + section.text())
				.collect(Collectors.joining("\n\n"));
	}
}

package com.neowadaeum.ai.provider.gemini;

import com.neowadaeum.ai.provider.AiPurpose;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Gemini 접속 정보와 용도별 모델 설정 (B-22-1, R3.1 · R3.6). */
@ConfigurationProperties("ai.providers.gemini")
public record GeminiProperties(String apiKey, Models models, String baseUrl, Integer maxTokens,
		Map<String, ModelPrice> pricing) {

	private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";

	private static final int DEFAULT_MAX_TOKENS = 4096;

	public GeminiProperties {
		baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl;
		maxTokens = (maxTokens == null) ? DEFAULT_MAX_TOKENS : maxTokens;
		models = (models == null) ? new Models(null, null, null, null) : models;
		pricing = (pricing == null) ? Map.of() : Map.copyOf(pricing);
	}

	public boolean configured() {
		return this.apiKey != null && !this.apiKey.isBlank() && modelFor(AiPurpose.TURN) != null;
	}

	public String modelFor(AiPurpose purpose) {
		String model = this.models.forPurpose(purpose);
		return (model == null || model.isBlank()) ? null : model;
	}

	public Long costMicroKrw(String modelId, Integer inputTokens, Integer outputTokens) {
		if (modelId == null || inputTokens == null || outputTokens == null) {
			return null;
		}
		ModelPrice price = this.pricing.get(modelId);
		return (price != null) ? price.costMicroKrw(inputTokens, outputTokens) : null;
	}

	/** 모델 용도는 합치지 않는다. 같은 모델을 쓰는 것은 설정의 선택이다 (I-12). */
	public record Models(String turn, String summary, String safety, String outline) {

		String forPurpose(AiPurpose purpose) {
			return switch (purpose) {
				case TURN -> this.turn;
				case SUMMARY -> this.summary;
				case SAFETY -> this.safety;
				case OUTLINE -> this.outline;
			};
		}
	}

	/** 100만 토큰당 KRW 단가. 둘 중 하나라도 없으면 비용을 모르는 것으로 남긴다 (§13-53). */
	public record ModelPrice(BigDecimal inputKrwPerMillionTokens, BigDecimal outputKrwPerMillionTokens) {

		private Long costMicroKrw(int inputTokens, int outputTokens) {
			if (this.inputKrwPerMillionTokens == null || this.outputKrwPerMillionTokens == null) {
				return null;
			}
			return this.inputKrwPerMillionTokens.multiply(BigDecimal.valueOf(inputTokens))
					.add(this.outputKrwPerMillionTokens.multiply(BigDecimal.valueOf(outputTokens)))
					.setScale(0, RoundingMode.HALF_UP)
					.longValueExact();
		}
	}
}

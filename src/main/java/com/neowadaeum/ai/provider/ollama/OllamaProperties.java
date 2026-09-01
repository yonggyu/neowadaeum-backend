package com.neowadaeum.ai.provider.ollama;

import com.neowadaeum.ai.provider.AiPurpose;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ollama 접속 정보 (B-23, R3.2).
 *
 * <p><b>API 키가 없다.</b> 로컬 런타임이라 인증이 없고, 그래서 {@code base-url} 이 등록 조건이다 —
 * <b>주소를 모르면 붙일 곳이 없다.</b>
 *
 * <p><b>기본값을 주지 않는다</b> (§7.3). {@code localhost:11434} 를 박아 두면 설정을 빠뜨린
 * 배포가 조용히 뜨고, 그 인스턴스는 <b>있지도 않은 로컬 모델을 계속 부른다.</b>
 *
 * @param baseUrl Ollama 서버 주소
 * @param models  용도별 모델 (R3.6). Anthropic 과 같은 축이다
 */
@ConfigurationProperties("ai.providers.ollama")
public record OllamaProperties(String baseUrl, Models models) {

	public OllamaProperties {
		models = (models == null) ? new Models(null, null, null, null) : models;
	}

	/** 용도별 모델. 구조는 {@code AnthropicProperties.Models} 와 같다 (R3.6). */
	public record Models(String turn, String summary, String safety, String outline) {

		public String forPurpose(AiPurpose purpose) {
			return switch (purpose) {
				case TURN -> this.turn;
				case SUMMARY -> this.summary;
				case SAFETY -> this.safety;
				case OUTLINE -> this.outline;
			};
		}
	}

	/** 주소와 턴 생성 모델이 둘 다 있어야 등록된다. */
	public boolean configured() {
		return this.baseUrl != null && !this.baseUrl.isBlank() && modelFor(AiPurpose.TURN) != null;
	}

	public String modelFor(AiPurpose purpose) {
		String model = this.models.forPurpose(purpose);
		return (model == null || model.isBlank()) ? null : model;
	}
}

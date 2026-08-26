package com.neowadaeum.ai.provider.ollama;

import com.neowadaeum.ai.log.AiCallRecorder;
import com.neowadaeum.ai.prompt.TurnPromptFactory;
import com.neowadaeum.ai.schema.TurnOutputParser;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Ollama 어댑터 등록 경계 (B-23, R3.1).
 *
 * <p>{@code AnthropicProviderConfiguration} 과 같은 규칙이다 — <b>설정이 갖춰졌을 때만 등록한다.</b>
 * 여기서는 API 키가 없는 대신 {@code base-url} 이 그 자리를 대신한다: <b>주소를 모르면 붙일 곳이
 * 없다.</b>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OllamaProperties.class)
public class OllamaProviderConfiguration {

	@Bean
	public OllamaStoryProvider ollamaStoryProvider(OllamaProperties properties, TurnPromptFactory prompts,
			TurnOutputParser parser, AiCallRecorder recorder) {

		if (!properties.configured()) {
			return null;
		}

		return new OllamaStoryProvider(RestClient.builder().baseUrl(properties.baseUrl()).build(),
				properties, prompts, parser, recorder);
	}
}

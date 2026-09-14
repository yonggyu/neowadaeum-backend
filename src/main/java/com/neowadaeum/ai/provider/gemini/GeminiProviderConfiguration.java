package com.neowadaeum.ai.provider.gemini;

import com.neowadaeum.ai.log.AiCallRecorder;
import com.neowadaeum.ai.prompt.TurnPromptFactory;
import com.neowadaeum.ai.provider.ProviderHttpClients;
import com.neowadaeum.ai.provider.ProviderProperties;
import com.neowadaeum.ai.schema.TurnOutputParser;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** 키와 턴 모델이 모두 있을 때만 Gemini 어댑터를 등록한다 (B-22-1, §7.3). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ GeminiProperties.class, ProviderProperties.class })
public class GeminiProviderConfiguration {

	static RestClient restClient(GeminiProperties properties, ProviderProperties providerProperties) {
		return RestClient.builder()
				.baseUrl(properties.baseUrl())
				.defaultHeader("x-goog-api-key", properties.apiKey())
				.defaultHeader("content-type", "application/json")
				.requestFactory(ProviderHttpClients.requestFactory(providerProperties))
				.build();
	}

	@Bean
	public GeminiStoryProvider geminiStoryProvider(GeminiProperties properties,
			ProviderProperties providerProperties, TurnPromptFactory prompts, TurnOutputParser parser,
			AiCallRecorder recorder) {
		if (!properties.configured()) {
			return null;
		}
		return new GeminiStoryProvider(restClient(properties, providerProperties), properties, prompts, parser,
				recorder);
	}
}

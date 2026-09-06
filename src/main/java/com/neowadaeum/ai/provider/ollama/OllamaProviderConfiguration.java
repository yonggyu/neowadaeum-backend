package com.neowadaeum.ai.provider.ollama;

import com.neowadaeum.ai.log.AiCallRecorder;
import com.neowadaeum.ai.prompt.TurnPromptFactory;
import com.neowadaeum.ai.provider.ProviderHttpClients;
import com.neowadaeum.ai.provider.ProviderProperties;
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
 *
 * <p><b>요청 팩토리도 같은 규칙이다</b> (#374). 여기에는 오래 그것이 없었고, 그동안 이 어댑터의
 * HTTP 스택은 <b>그때 클래스패스에 무엇이 있느냐</b>로 정해졌다 — 근거는
 * {@link ProviderHttpClients} 에 있다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ OllamaProperties.class, ProviderProperties.class })
public class OllamaProviderConfiguration {

	/**
	 * <b>테스트가 같은 것을 쓴다</b> (#93 이 Anthropic 에서 세운 규칙, #374 에서 이쪽에도 적용).
	 * 계약 테스트가 자기 {@code RestClient} 를 손으로 만들면 요청 팩토리와 타임아웃이 운영과
	 * 갈라지고, <b>그 차이는 테스트가 통과하는 방식으로 숨는다.</b>
	 */
	static RestClient restClient(OllamaProperties properties, ProviderProperties providerProperties) {
		return RestClient.builder()
				.baseUrl(properties.baseUrl())
				.requestFactory(ProviderHttpClients.requestFactory(providerProperties))
				.build();
	}

	@Bean
	public OllamaStoryProvider ollamaStoryProvider(OllamaProperties properties,
			ProviderProperties providerProperties, TurnPromptFactory prompts, TurnOutputParser parser,
			AiCallRecorder recorder) {

		if (!properties.configured()) {
			return null;
		}

		return new OllamaStoryProvider(restClient(properties, providerProperties), properties, prompts, parser,
				recorder);
	}
}

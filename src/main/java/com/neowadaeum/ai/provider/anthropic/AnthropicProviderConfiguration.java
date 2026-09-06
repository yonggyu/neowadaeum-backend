package com.neowadaeum.ai.provider.anthropic;

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
 * Anthropic 어댑터 등록 경계 (B-22, R3.1).
 *
 * <p><b>설정이 갖춰졌을 때만 등록한다.</b> 키나 모델이 없으면 빈을 만들지 않는다 — 그 상태로
 * 등록되면 <b>첫 턴 요청에서야</b> 설정 누락이 드러나고, 그때 사용자는 502 를 본다.
 *
 * <p><b>지목받았는데 없으면 부팅이 멈춘다.</b> {@code ai.provider.active=anthropic} 인데 이 빈이
 * 없으면 {@code ProviderRegistry} 가 거절한다 (B-18). <b>조용히 다른 어댑터로 넘어가지 않는
 * 것</b>이 요점이다 — 운영에서 어느 모델이 도는지 아무도 모르는 상태가 최악이다.
 *
 * <p><b>{@code prod} 를 배제하지 않는다.</b> 결정론 Provider(S-3)와 반대다 — 이것이 운영에서
 * 실제로 도는 어댑터다. 켜고 끄는 것은 프로파일이 아니라 설정이다 (R3.1, I-14).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ AnthropicProperties.class, ProviderProperties.class })
public class AnthropicProviderConfiguration {

	/**
	 * <b>테스트가 같은 것을 쓴다.</b> 계약 테스트가 자기 {@code RestClient} 를 손으로 만들면
	 * 요청 팩토리 · 헤더 · 타임아웃이 운영과 갈라지고, <b>그 차이는 테스트가 통과하는 방식으로
	 * 숨는다</b> — 실제로 그랬다 (#93 점검).
	 *
	 * <p>요청 팩토리와 상한의 근거는 {@link ProviderHttpClients} 에 있다. 벤더마다 복제하지
	 * 않는다 — 복제하면 한쪽만 고쳐지는 날 <b>어긋난 채로 조용히 통과한다.</b>
	 */
	static RestClient restClient(AnthropicProperties properties, ProviderProperties providerProperties) {
		return RestClient.builder()
				.baseUrl(properties.baseUrl())
				.defaultHeader("x-api-key", properties.apiKey())
				.defaultHeader("anthropic-version", "2023-06-01")
				.defaultHeader("content-type", "application/json")
				.requestFactory(ProviderHttpClients.requestFactory(providerProperties))
				.build();
	}

	@Bean
	public TurnOutputParser turnOutputParser() {
		return new TurnOutputParser();
	}

	/**
	 * 설정이 없으면 {@code null} 을 돌려 <b>빈을 만들지 않는다.</b>
	 *
	 * <p>{@code @ConditionalOnProperty} 를 쓰지 않은 이유는 조건이 <b>두 값의 조합</b>이기
	 * 때문이다 — 키만 있고 모델이 없는 상태도 등록하면 안 된다.
	 */
	@Bean
	public AnthropicStoryProvider anthropicStoryProvider(AnthropicProperties properties,
			ProviderProperties providerProperties, TurnPromptFactory prompts, TurnOutputParser parser,
			AiCallRecorder recorder) {

		if (!properties.configured()) {
			return null;
		}

		return new AnthropicStoryProvider(restClient(properties, providerProperties), properties, prompts, parser,
				recorder);
	}
}

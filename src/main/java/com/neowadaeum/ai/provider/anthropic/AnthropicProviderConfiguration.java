package com.neowadaeum.ai.provider.anthropic;

import com.neowadaeum.ai.prompt.TurnPromptFactory;
import com.neowadaeum.ai.schema.TurnOutputParser;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import java.net.http.HttpClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
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
@EnableConfigurationProperties(AnthropicProperties.class)
public class AnthropicProviderConfiguration {

	/**
	 * <b>읽기 상한을 클라이언트에 둔다.</b> 시간 제한과 취소는 {@code TimeLimitedStoryProvider} 의
	 * 일이지만 (R6.4), 그 취소는 <b>인터럽트</b>로 닿는다 — 블로킹 소켓 읽기는 인터럽트에 항상
	 * 반응하지 않으므로, 상한이 없으면 취소된 호출의 스레드가 살아남는다.
	 *
	 * <p>값이 생성 예산보다 넉넉한 것은 의도다. 여기서 끊는 것은 <b>정상적인 상한</b>이 아니라
	 * <b>영영 오지 않는 응답</b>이며, 정상 상한은 §13-19 의 25초가 담당한다.
	 */
	private static final Duration SOCKET_READ_CEILING = Duration.ofSeconds(60);

	/**
	 * JDK {@code HttpClient} 기반 팩토리.
	 *
	 * <p>{@code connectTimeout} 은 클라이언트가, 읽기 상한은 팩토리가 갖는다 — JDK 클라이언트에는
	 * 읽기 타임아웃 개념이 없고 요청 단위 {@code timeout} 으로 표현된다.
	 */
	private static JdkClientHttpRequestFactory requestFactory() {
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
		factory.setReadTimeout(SOCKET_READ_CEILING);
		return factory;
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
			TurnPromptFactory prompts, TurnOutputParser parser) {

		if (!properties.configured()) {
			return null;
		}

		RestClient restClient = RestClient.builder()
				.baseUrl(properties.baseUrl())
				.defaultHeader("x-api-key", properties.apiKey())
				.defaultHeader("anthropic-version", "2023-06-01")
				.defaultHeader("content-type", "application/json")
				.requestFactory(requestFactory())
				.build();

		return new AnthropicStoryProvider(restClient, properties, prompts, parser);
	}
}

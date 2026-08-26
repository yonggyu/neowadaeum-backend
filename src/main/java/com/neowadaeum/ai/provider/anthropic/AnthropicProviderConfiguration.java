package com.neowadaeum.ai.provider.anthropic;

import com.neowadaeum.ai.log.AiCallRecorder;
import com.neowadaeum.ai.prompt.TurnPromptFactory;
import com.neowadaeum.ai.provider.ProviderProperties;
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
@EnableConfigurationProperties({ AnthropicProperties.class, ProviderProperties.class })
public class AnthropicProviderConfiguration {

	/**
	 * 소켓 읽기 상한을 <b>생성 예산에서 파생시키는 배수</b>.
	 *
	 * <p><b>왜 상한이 필요한가.</b> 시간 제한과 취소는 {@code TimeLimitedStoryProvider} 의 일이고
	 * (R6.4), 그 취소는 <b>인터럽트</b>로 닿는다. JDK {@code HttpClient} 는 인터럽트에 반응하며
	 * 그것을 {@code AnthropicCancellationTests} 가 못박는다 — 그러나 그 성질이 깨지는 날 상한이
	 * 없으면 <b>취소된 호출의 스레드가 영영 살아남는다.</b>
	 *
	 * <p><b>왜 예산보다 커야 하는가.</b> 상한이 예산보다 작거나 같으면 <b>상한이 먼저 끊는다.</b>
	 * 그러면 시간 초과가 {@code 504 GENERATION_TIMEOUT} 이 아니라 호출 실패 → {@code 502
	 * PROVIDER_ERROR} 로 나가고, <b>원인은 시간인데 표시는 벤더 장애가 된다.</b> §13-19 는 예산의
	 * 권한을 데코레이터에 뒀고 이 값은 그 결정을 침범하면 안 된다.
	 *
	 * <p><b>왜 상수가 아니라 파생인가.</b> {@code ai.provider.timeout-ms} 는 설정값이다. 60초처럼
	 * 고정해 두면 예산을 그보다 크게 잡는 순간 위 오분류가 조용히 생긴다 — 설정 하나를 바꿨을 뿐인데
	 * 에러 코드가 바뀐다. 재요청은 예산 <b>안쪽</b>에서 돌므로(§13-19) 예산 하나만 기준으로 삼으면
	 * 충분하다.
	 */
	private static final int SOCKET_READ_CEILING_FACTOR = 2;

	/**
	 * <b>테스트가 같은 것을 쓴다.</b> 계약 테스트가 자기 {@code RestClient} 를 손으로 만들면
	 * 요청 팩토리 · 헤더 · 타임아웃이 운영과 갈라지고, <b>그 차이는 테스트가 통과하는 방식으로
	 * 숨는다</b> — 실제로 그랬다 (#93 점검).
	 */
	static RestClient restClient(AnthropicProperties properties, ProviderProperties providerProperties) {
		return RestClient.builder()
				.baseUrl(properties.baseUrl())
				.defaultHeader("x-api-key", properties.apiKey())
				.defaultHeader("anthropic-version", "2023-06-01")
				.defaultHeader("content-type", "application/json")
				.requestFactory(requestFactory(socketReadCeiling(providerProperties)))
				.build();
	}

	/**
	 * JDK {@code HttpClient} 기반 팩토리.
	 *
	 * <p>{@code connectTimeout} 은 클라이언트가, 읽기 상한은 팩토리가 갖는다 — JDK 클라이언트에는
	 * 읽기 타임아웃 개념이 없고 요청 단위 {@code timeout} 으로 표현된다.
	 */
	private static JdkClientHttpRequestFactory requestFactory(Duration readCeiling) {
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
		factory.setReadTimeout(readCeiling);
		return factory;
	}

	/** 생성 예산의 {@value #SOCKET_READ_CEILING_FACTOR} 배. 근거는 상수 주석에 있다. */
	static Duration socketReadCeiling(ProviderProperties providerProperties) {
		return providerProperties.timeoutMs().multipliedBy(SOCKET_READ_CEILING_FACTOR);
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

package com.neowadaeum.ai.provider;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * 어댑터가 벤더에게 말할 때 쓰는 HTTP 클라이언트 (#93 · #374).
 *
 * <p><b>와이어 클라이언트는 클래스패스가 아니라 여기가 고른다.</b> {@code RestClient.builder()} 를
 * 요청 팩토리 없이 쓰면 스프링이 <b>그때 클래스패스에 있는 것</b>을 집는다 — Apache · Jetty ·
 * Reactor · JDK 순이다. 그러면 <b>AI 와 아무 상관 없는 의존 하나가 모델과 말하는 방식을 바꾼다.</b>
 * 실제로 그랬다: #315 가 객체 저장소 SDK 를 더하면서 Apache HttpClient 5 가 함께 들어왔고, 그날
 * {@code OllamaProviderConfiguration} 의 클라이언트는 <b>아무도 그 파일을 건드리지 않은 채로</b>
 * JDK 에서 Apache 로 바뀌었다.
 *
 * <p><b>JDK {@code HttpClient} 를 고른다.</b> 취소가 <b>인터럽트</b>로 닿아야 하기 때문이다 —
 * {@code TimeLimitedStoryProvider} 가 예산을 넘긴 호출을 {@link java.util.concurrent.Future#cancel}
 * 로 끊고(R6.4), 그것이 실제 HTTP 호출까지 닿는지는 {@code AnthropicCancellationTests} 가 못박고
 * 있다. Apache 의 블로킹 소켓 읽기는 인터럽트에 반응하지 않는다.
 *
 * <p><b>그래도 소켓 읽기 상한을 함께 건다.</b> 인터럽트가 닿는다는 성질이 깨지는 날 상한이 없으면
 * <b>취소된 호출의 스레드가 영영 살아남는다.</b> 로컬 모델이 가중치를 올리다 멈추는 것은 드문
 * 상황이 아니고, 그 한 번이 생성 실행기의 스레드를 하나씩 영구히 가져간다.
 */
public final class ProviderHttpClients {

	/**
	 * 소켓 읽기 상한을 <b>생성 예산에서 파생시키는 배수</b>.
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

	/** 연결 수립 상한. 읽기와 달리 예산과 무관하게 짧다 — 붙지 못하는 주소는 기다릴 값이 없다. */
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

	private ProviderHttpClients() {
	}

	/**
	 * JDK {@code HttpClient} 기반 팩토리.
	 *
	 * <p>{@code connectTimeout} 은 클라이언트가, 읽기 상한은 팩토리가 갖는다 — JDK 클라이언트에는
	 * 읽기 타임아웃 개념이 없고 요청 단위 {@code timeout} 으로 표현된다.
	 */
	public static JdkClientHttpRequestFactory requestFactory(ProviderProperties providerProperties) {
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
		factory.setReadTimeout(socketReadCeiling(providerProperties));
		return factory;
	}

	/** 생성 예산의 {@value #SOCKET_READ_CEILING_FACTOR} 배. 근거는 상수 주석에 있다. */
	public static Duration socketReadCeiling(ProviderProperties providerProperties) {
		return providerProperties.timeoutMs().multipliedBy(SOCKET_READ_CEILING_FACTOR);
	}
}

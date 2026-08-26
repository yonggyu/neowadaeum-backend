package com.neowadaeum.ai.provider.anthropic;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.neowadaeum.ai.prompt.PromptAssembler;
import com.neowadaeum.common.support.RecentTurnsProperties;
import com.neowadaeum.ai.prompt.TurnPromptFactory;
import com.neowadaeum.ai.provider.ProviderProperties;
import com.neowadaeum.ai.provider.TimeLimitedStoryProvider;
import com.neowadaeum.ai.schema.TurnOutputParser;
import com.neowadaeum.common.support.FixedTokenCounter;
import com.neowadaeum.play.port.GenerationContexts;
import com.neowadaeum.play.port.ProviderCallFailedException;
import com.neowadaeum.play.port.GenerationTimedOutException;
import com.neowadaeum.play.port.TurnRequest;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>느린 어댑터가 실제로 끊기는가</b> (B-22 DoD, R6.4).
 *
 * <p>기존 {@code TimeLimitedStoryProviderTests} 는 <b>{@code Thread.sleep} 으로 느린 척하는</b>
 * 가짜 Provider 를 썼다. 그것으로 확인되는 것은 데코레이터의 계산이고, <b>실제 HTTP 호출이
 * 인터럽트에 반응하는지</b>는 아니다 — 블로킹 소켓 읽기는 인터럽트에 항상 반응하지 않는다.
 *
 * <p>여기서는 진짜 서버가 응답을 지연시킨다. 컨테이너는 필요 없다 (ADR-0001).
 */
class AnthropicTimeoutContractTests {

	/** 초과를 <b>기대하는</b> 테스트라 짧아서 생기는 오차가 결과를 바꾸지 않는다 — 느릴수록 기대대로 간다. */
	/** 기록된 호출. B-25 이후 어댑터가 필수로 요구한다 — 무엇이 남는지도 함께 본다. */
	private final java.util.List<com.neowadaeum.ai.log.AiCallLog.Draft> recorded =
			java.util.Collections.synchronizedList(new java.util.ArrayList<>());

	private static final Duration SHORT_BUDGET = Duration.ofMillis(300);

	/** 예산보다 훨씬 길게 끈다. 이 값을 기다리게 되면 취소가 닿지 않은 것이다. */
	private static final int SERVER_DELAY_MS = 30_000;

	private WireMockServer server;

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	@BeforeEach
	void startServer() {
		this.server = new WireMockServer(WireMockConfiguration.options().dynamicPort().http2PlainDisabled(true));
		this.server.start();
		this.server.stubFor(post(urlEqualTo("/v1/messages"))
				.willReturn(aResponse().withStatus(200).withFixedDelay(SERVER_DELAY_MS)));
	}

	@AfterEach
	void stopServer() {
		this.server.stop();
		this.executor.shutdownNow();
	}

	/**
	 * R6.4 — <b>예산을 넘기면 끊는다. 그리고 서버 지연만큼 기다리지 않는다.</b>
	 *
	 * <p>경과 시간을 함께 보는 것이 요점이다. 예외만 확인하면 <b>30초를 기다린 뒤 예외가 나와도</b>
	 * 통과한다 — 그때 사용자는 이미 떠났고 비용은 청구된 뒤다.
	 */
	@Test
	void R6_4_a_slow_provider_is_cut_off_and_does_not_wait_for_the_server() {
		TimeLimitedStoryProvider provider = new TimeLimitedStoryProvider(adapter(), this.executor, SHORT_BUDGET);

		long startedAt = System.nanoTime();
		assertThatThrownBy(() -> provider.generateTurn(request()))
				.isInstanceOf(GenerationTimedOutException.class);
		Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

		assertThat(elapsed)
				.as("서버 지연(%dms)만큼 기다렸다면 취소가 닿지 않은 것이다", SERVER_DELAY_MS)
				.isLessThan(Duration.ofSeconds(10));
	}

	/**
	 * <b>세션 상태는 그대로다</b> (R6.6) — 이 경로는 §6.1 의 7단계 이전에서 끊긴다.
	 *
	 * <p>여기서 확인할 수 있는 것은 예외의 종류까지다. 상태가 실제로 변하지 않는다는 것은
	 * 파이프라인 통합 테스트가 본다.
	 */
	@Test
	void R6_6_the_timeout_is_reported_as_a_timeout_not_as_a_call_failure() {
		TimeLimitedStoryProvider provider = new TimeLimitedStoryProvider(adapter(), this.executor, SHORT_BUDGET);

		assertThatThrownBy(() -> provider.generateTurn(request()))
				.as("호출 실패로 뭉뚱그리면 504 가 502 가 된다")
				.isNotInstanceOf(ProviderCallFailedException.class)
				.isInstanceOf(GenerationTimedOutException.class);
	}

	private AnthropicStoryProvider adapter() {
		AnthropicProperties properties = new AnthropicProperties("test-key", turnModel("claude-opus-5"),
				"http://localhost:" + this.server.port(), 4096);

		return new AnthropicStoryProvider(
				AnthropicProviderConfiguration.restClient(properties,
						new ProviderProperties(Duration.ofSeconds(30), null)),
				properties,
				new TurnPromptFactory(new PromptAssembler(new FixedTokenCounter(), RecentTurnsProperties.defaults())),
				new TurnOutputParser(),
				this.recorded::add);
	}

	private static TurnRequest request() {
		return TurnRequest.opening(UUID.randomUUID(), GenerationContexts.populated());
	}
	/** 턴 생성 모델만 채운 설정. 용도별 분리(B-24) 이후 대부분의 테스트가 필요로 하는 최소 형태다. */
	private static AnthropicProperties.Models turnModel(String model) {
		return new AnthropicProperties.Models(model, null, null, null);
	}

}

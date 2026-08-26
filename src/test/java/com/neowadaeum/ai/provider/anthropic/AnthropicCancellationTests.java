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
import com.neowadaeum.ai.provider.OutlineRequest;
import com.neowadaeum.ai.provider.OutlineResult;
import com.neowadaeum.ai.provider.ProviderCapabilities;
import com.neowadaeum.ai.provider.ProviderProperties;
import com.neowadaeum.ai.provider.StoryProvider;
import com.neowadaeum.ai.provider.SummaryRequest;
import com.neowadaeum.ai.provider.TimeLimitedStoryProvider;
import com.neowadaeum.ai.schema.TurnOutputParser;
import com.neowadaeum.common.support.FixedTokenCounter;
import com.neowadaeum.play.port.GeneratedTurn;
import com.neowadaeum.play.port.GenerationContexts;
import com.neowadaeum.play.port.GenerationTimedOutException;
import com.neowadaeum.play.port.TurnRequest;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>취소가 실제 HTTP 호출까지 닿는가</b> (#93 머지 전 점검, R6.4, §13-19).
 *
 * <p><b>호출자가 빨리 돌아오는 것만으로는 부족하다.</b> {@code TimeLimitedStoryProvider} 는
 * {@code Future.get(timeout)} 으로 기다리다 포기하므로, <b>취소가 닿지 않아도 호출자는 제때
 * 예외를 받는다.</b> 그 사이 실제 요청은 소켓 읽기 상한(60초)까지 살아 있을 수 있고, 그러면
 * §13-19 가 정한 <b>재요청 포함 25초 예산이 스레드와 연결 수준에서 깨진다.</b>
 *
 * <p>그래서 여기서 세는 것은 <b>호출자가 아니라 아래쪽 호출이 언제 끝나는가</b>다. 위임 Provider 의
 * {@code finally} 가 래치를 내리며, 인터럽트가 무시되면 그 래치는 서버 지연이 끝날 때까지 내려오지
 * 않는다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class AnthropicCancellationTests {

	private static final Duration SHORT_BUDGET = Duration.ofMillis(300);

	/** 예산보다 훨씬 길게 끈다. 인터럽트가 무시되면 아래쪽 호출이 이만큼 살아 있다. */
	private static final int SERVER_DELAY_MS = 30_000;

	/**
	 * <b>어댑터의 읽기 상한을 멀리 둔다.</b>
	 *
	 * <p>이 값이 짧으면 <b>취소와 무관하게 상한이 호출을 끝내고</b>, 그러면 이 파일의 테스트는
	 * 전부 잘못된 이유로 통과한다 — 실제로 그랬다 (#97). 상한은 예산의 2배이므로 여기에 넉넉한
	 * 값을 넣어 <b>인터럽트만이 호출을 빨리 끝낼 수 있는 상태</b>를 만든다.
	 */
	private static final Duration FAR_CEILING_BUDGET = Duration.ofSeconds(30);

	/**
	 * 아래쪽 호출이 끝나기를 기다리는 상한.
	 *
	 * <p>예산(300ms)보다 넉넉하고 서버 지연(30초)보다 훨씬 짧다. 이 안에 안 끝나면 <b>취소가
	 * 닿지 않은 것</b>이며, 그 사이가 곧 예산 밖에서 살아 있는 시간이다.
	 */
	private static final Duration CANCELLATION_DEADLINE = Duration.ofSeconds(5);

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
	 * <b>이 PR 의 DoD 중 "취소 동작"이 실제로 뜻하는 것.</b>
	 *
	 * <p>호출자가 {@code 504} 를 받는 것과 <b>요청이 실제로 끝나는 것</b>은 다른 사건이다. 둘을
	 * 같이 확인하지 않으면 "취소했다"가 "기다리다 말았다"와 구분되지 않는다.
	 */
	@Test
	void R6_4_the_underlying_http_call_actually_ends_when_the_budget_is_cut() throws InterruptedException {
		CountDownLatch callEnded = new CountDownLatch(1);
		TimeLimitedStoryProvider provider = new TimeLimitedStoryProvider(
				watching(adapter(), callEnded), this.executor, SHORT_BUDGET);

		assertThatThrownBy(() -> provider.generateTurn(request()))
				.isInstanceOf(GenerationTimedOutException.class);

		assertThat(callEnded.await(CANCELLATION_DEADLINE.toMillis(), TimeUnit.MILLISECONDS))
				.as("취소가 실제 HTTP 호출에 닿지 않았다 — 요청이 %s 를 넘겨 살아 있다. "
						+ "§13-19 의 25초 예산이 스레드·연결 수준에서 깨진다", CANCELLATION_DEADLINE)
				.isTrue();
	}

	/**
	 * <b>음성 대조군</b> — 취소 전달이 사라지면 위 테스트가 실제로 실패하는가 (#97).
	 *
	 * <p>통과하는 테스트가 무엇을 지키는지 모르는 상태로 두지 않는다. 여기서는 <b>인터럽트를
	 * 삼키는 실행기</b>로 같은 시나리오를 돌린다 — {@code Future.cancel(true)} 가 스레드에 닿지
	 * 않으므로 아래쪽 호출은 서버 지연이 끝날 때까지 살아 있어야 한다.
	 *
	 * <p>이 테스트가 <b>실패하면</b> 위 테스트도 의미가 없다는 뜻이다 — 취소와 무관하게 래치가
	 * 내려온다는 말이기 때문이다.
	 */
	@Test
	void S97_the_cancellation_test_would_fail_if_cancellation_stopped_propagating() throws InterruptedException {
		CountDownLatch callEnded = new CountDownLatch(1);
		// close() 를 부르지 않는다 — 삼켜진 인터럽트 때문에 종료를 기다리며 멈춘다. 스레드는 데몬이다.
		ExecutorService uninterruptible = Executors.newSingleThreadExecutor(NonInterruptibleThread::new);
		TimeLimitedStoryProvider provider = new TimeLimitedStoryProvider(
				watching(adapter(), callEnded), uninterruptible, SHORT_BUDGET);

		assertThatThrownBy(() -> provider.generateTurn(request()))
				.isInstanceOf(GenerationTimedOutException.class);

		assertThat(callEnded.await(2, TimeUnit.SECONDS))
				.as("인터럽트가 삼켜졌는데도 호출이 끝났다면, 위 테스트는 취소가 아니라 다른 것을 보고 있다")
				.isFalse();
	}

	/**
	 * 인터럽트를 삼키는 스레드.
	 *
	 * <p>{@code Thread.interrupt()} 를 무시하므로 블로킹 읽기가 깨어나지 않는다 — 취소가 닿지
	 * 않는 런타임을 흉내 낸다.
	 */
	private static final class NonInterruptibleThread extends Thread {

		private NonInterruptibleThread(Runnable task) {
			super(task);
			setDaemon(true);
		}

		@Override
		public void interrupt() {
			// 삼킨다. 이것이 이 대조군의 전부다.
		}
	}

	/** 운영과 같은 방식으로 만든 클라이언트를 쓴다 — 요청 팩토리와 타임아웃이 갈라지면 의미가 없다. */
	private AnthropicStoryProvider adapter() {
		AnthropicProperties properties = new AnthropicProperties("test-key", turnModel("claude-opus-5"),
				"http://localhost:" + this.server.port(), 4096);

		return new AnthropicStoryProvider(
				AnthropicProviderConfiguration.restClient(properties,
						new ProviderProperties(FAR_CEILING_BUDGET, null)),
				properties,
				new TurnPromptFactory(new PromptAssembler(new FixedTokenCounter(), RecentTurnsProperties.defaults())),
				new TurnOutputParser());
	}

	/** 아래쪽 호출이 <b>언제 끝나는지</b>를 기록한다. 성공이든 실패든 {@code finally} 가 내린다. */
	private static StoryProvider watching(StoryProvider delegate, CountDownLatch callEnded) {
		return new StoryProvider() {
			@Override
			public String providerId() {
				return delegate.providerId();
			}

			@Override
			public ProviderCapabilities capabilities() {
				return delegate.capabilities();
			}

			@Override
			public GeneratedTurn generateTurn(TurnRequest request) {
				try {
					return delegate.generateTurn(request);
				}
				finally {
					callEnded.countDown();
				}
			}

			@Override
			public String summarize(SummaryRequest request) {
				return delegate.summarize(request);
			}

			@Override
			public OutlineResult draftOutline(OutlineRequest request) {
				return delegate.draftOutline(request);
			}
		};
	}

	private static TurnRequest request() {
		return TurnRequest.opening(UUID.randomUUID(), GenerationContexts.populated());
	}
	/** 턴 생성 모델만 채운 설정. 용도별 분리(B-24) 이후 대부분의 테스트가 필요로 하는 최소 형태다. */
	private static AnthropicProperties.Models turnModel(String model) {
		return new AnthropicProperties.Models(model, null, null, null);
	}

}

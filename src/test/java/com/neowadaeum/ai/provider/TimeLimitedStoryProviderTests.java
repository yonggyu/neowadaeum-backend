package com.neowadaeum.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-9-3 (#67) — R6.4 의 시간 제한이 <b>실제로 끊고 취소하는지</b> 확인한다.
 *
 * <p>컨테이너가 필요 없다. 짧은 제한을 걸어 초 단위로 기다리지 않는다 (ADR-0001).
 */
class TimeLimitedStoryProviderTests {

	/**
	 * 초과를 만들 때 쓰는 값. 짧을수록 테스트가 빠르다.
	 *
	 * <p>초과를 <b>기대하는</b> 테스트에서는 짧아서 생기는 오차가 결과를 바꾸지 않는다 — 느릴수록
	 * 기대대로 간다.
	 */
	private static final Duration SHORT = Duration.ofMillis(200);

	/**
	 * 통과를 확인할 때 쓰는 값.
	 *
	 * <p>여기서는 짧게 잡으면 안 된다. 초안이 100ms 였고 <b>정상 경로가 타임아웃으로 잡혔다</b> —
	 * 500ms 로 올려도 마찬가지였다. 첫 호출에서 가상 스레드 기반과 Jackson 초기화가 함께 일어나며,
	 * 그 비용은 제한 시간이 아니라 <b>런타임 준비</b>다. 넉넉히 두고 통과 여부만 본다.
	 */
	private static final Duration GENEROUS = Duration.ofSeconds(10);

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	@AfterEach
	void shutdown() {
		this.executor.shutdownNow();
	}

	private static TurnRequest request() {
		return TurnRequest.opening(UUID.randomUUID());
	}

	private static TurnResult answer() {
		return new TurnResult("본문", List.of(new TurnResult.ProposedChoice(1, "선택")),
				JsonMapper.builder().build().readTree("{}"), false, null);
	}

	/** 제한 안에 답하면 그대로 통과한다. 정상 경로가 느려지면 안 된다. */
	@Test
	void R6_4_a_prompt_answer_passes_through() {
		TimeLimitedStoryProvider provider = new TimeLimitedStoryProvider(new TurnOnlyStoryProvider() {
			@Override
			public String providerId() {
				return "prompt";
			}

			@Override
			public TurnResult generateTurn(TurnRequest ignored) {
				return answer();
			}
		}, this.executor, GENEROUS);

		assertThat(provider.generateTurn(request()).narrative()).isEqualTo("본문");
		assertThat(provider.providerId()).isEqualTo("prompt");
	}

	/** R6.4 — 제한을 넘기면 예외다. 호출자는 이것을 504 로 바꾼다. */
	@Test
	void R6_4_a_slow_answer_times_out() {
		TimeLimitedStoryProvider provider = new TimeLimitedStoryProvider(sleeping(new CountDownLatch(1)),
				this.executor, SHORT);

		assertThatThrownBy(() -> provider.generateTurn(request()))
				.isInstanceOf(TimeLimitedStoryProvider.GenerationTimedOutException.class);
	}

	/**
	 * <b>포기하는 것으로 끝내지 않고 취소한다.</b>
	 *
	 * <p>기다리다 말기만 하면 뒤에서 계속 도는 호출이 남고, 그 비용은 그대로 청구된다.
	 * 인터럽트가 실제로 닿는지 본다.
	 */
	@Test
	void R6_4_the_pending_call_is_actually_cancelled() throws Exception {
		CountDownLatch interrupted = new CountDownLatch(1);
		AtomicBoolean sawInterrupt = new AtomicBoolean();

		TimeLimitedStoryProvider provider = new TimeLimitedStoryProvider(new TurnOnlyStoryProvider() {
			@Override
			public String providerId() {
				return "slow";
			}

			@Override
			public TurnResult generateTurn(TurnRequest ignored) {
				try {
					Thread.sleep(Duration.ofSeconds(30));
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					sawInterrupt.set(true);
					interrupted.countDown();
				}
				return answer();
			}
		}, this.executor, SHORT);

		assertThatThrownBy(() -> provider.generateTurn(request()))
				.isInstanceOf(TimeLimitedStoryProvider.GenerationTimedOutException.class);

		assertThat(interrupted.await(5, TimeUnit.SECONDS)).as("취소가 호출에 닿지 않았다").isTrue();
		assertThat(sawInterrupt).isTrue();
	}

	/** 위임이 던진 예외는 그대로 올린다. 타임아웃으로 뭉뚱그리면 원인이 사라진다. */
	@Test
	void R6_4_a_delegate_failure_is_not_disguised_as_a_timeout() {
		TimeLimitedStoryProvider provider = new TimeLimitedStoryProvider(new TurnOnlyStoryProvider() {
			@Override
			public String providerId() {
				return "failing";
			}

			@Override
			public TurnResult generateTurn(TurnRequest ignored) {
				throw new IllegalStateException("provider said no");
			}
		}, this.executor, GENEROUS);

		assertThatThrownBy(() -> provider.generateTurn(request()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("provider said no");
	}

	/** S-3 — 예외 메시지에 요청 내용을 담지 않는다. 예외는 로그로 흐른다. */
	@Test
	void S3_timeout_message_carries_no_request_content() {
		TimeLimitedStoryProvider provider = new TimeLimitedStoryProvider(sleeping(new CountDownLatch(1)),
				this.executor, SHORT);
		UUID storyVersion = UUID.randomUUID();

		assertThatThrownBy(() -> provider.generateTurn(TurnRequest.opening(storyVersion)))
				.hasMessageNotContaining(storyVersion.toString());
	}

	/**
	 * <b>제한 시간은 주입된 값이다</b> (#25).
	 *
	 * <p>같은 위임을 서로 다른 제한으로 감싸면 결과가 갈린다 — 하나는 통과하고 하나는 끊긴다.
	 * 상수를 재확인하는 테스트가 아니라 <b>설정된 값이 지켜지는지</b>를 본다.
	 */
	@Test
	void R6_4_the_configured_timeout_is_what_is_enforced() {
		CountDownLatch never = new CountDownLatch(1);

		assertThatThrownBy(() -> new TimeLimitedStoryProvider(sleeping(never), this.executor, SHORT)
				.generateTurn(request()))
				.isInstanceOf(TimeLimitedStoryProvider.GenerationTimedOutException.class);

		assertThat(new TimeLimitedStoryProvider(new TurnOnlyStoryProvider() {
			@Override
			public String providerId() {
				return "prompt";
			}

			@Override
			public TurnResult generateTurn(TurnRequest ignored) {
				return answer();
			}
		}, this.executor, GENEROUS).generateTurn(request()).narrative()).isEqualTo("본문");
	}

	private static StoryProvider sleeping(CountDownLatch never) {
		return new TurnOnlyStoryProvider() {
			@Override
			public String providerId() {
				return "slow";
			}

			@Override
			public TurnResult generateTurn(TurnRequest ignored) {
				try {
					never.await();
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
				return answer();
			}
		};
	}
}

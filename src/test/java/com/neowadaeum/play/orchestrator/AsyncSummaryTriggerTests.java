package com.neowadaeum.play.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * R4.6 — 요약은 <b>사용자 대기 시간 밖</b>이다 (B-34).
 *
 * <p><b>이 성질은 주석으로 지킬 수 없다.</b> 동기 호출로 바꾸는 변경은 컴파일도 되고 테스트도
 * 통과하며, 증상은 "턴이 느려졌다"로만 나타난다 — 원인이 요약이라는 것은 지연 분포를 보기 전까지
 * 모른다. 그래서 <b>돌아오는 시점</b>을 직접 확인한다.
 */
class AsyncSummaryTriggerTests {

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	@AfterEach
	void shutdown() {
		this.executor.shutdownNow();
	}

	/**
	 * <b>요약이 끝나기를 기다리지 않는다.</b>
	 *
	 * <p>요약기를 붙잡아 둔 채로 {@code afterTurn} 이 돌아오는지 본다. 동기였다면 여기서 멈춘다.
	 */
	@Test
	void R4_6_the_turn_does_not_wait_for_the_summary() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AsyncSummaryTrigger trigger = new AsyncSummaryTrigger(new StorySummarizer(null, null, null, null,
				null, null, null) {
			@Override
			public void compress(UUID sessionId, int currentTurnNo) {
				started.countDown();
				try {
					release.await();
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}
		}, this.executor);

		trigger.afterTurn(UUID.randomUUID(), 9);

		assertThat(started.await(5, TimeUnit.SECONDS)).as("요약이 시작되지 않았다").isTrue();
		// 요약기는 아직 붙잡혀 있다. 여기까지 왔다는 것이 곧 기다리지 않았다는 뜻이다.
		release.countDown();
	}

	/** 요약 실패가 밖으로 나가지 않는다 — 턴은 이미 응답된 뒤다 (R4.6). */
	@Test
	void R4_6_a_failing_summary_does_not_escape() {
		AsyncSummaryTrigger trigger = new AsyncSummaryTrigger(new StorySummarizer(null, null, null, null,
				null, null, null) {
			@Override
			public void compress(UUID sessionId, int currentTurnNo) {
				throw new IllegalStateException("요약 실패");
			}
		}, Runnable::run);

		assertThatCode(() -> trigger.afterTurn(UUID.randomUUID(), 9)).doesNotThrowAnyException();
	}

	/** 실행기가 거부해도 마찬가지다 — 다음 턴이 같은 구간을 다시 시도한다. */
	@Test
	void R4_6_a_rejected_task_does_not_escape() {
		AsyncSummaryTrigger trigger = new AsyncSummaryTrigger(new StorySummarizer(null, null, null, null,
				null, null, null) {
			@Override
			public void compress(UUID sessionId, int currentTurnNo) {
				throw new AssertionError("거부됐는데 실행됐다");
			}
		}, task -> {
			throw new RejectedExecutionException("shutting down");
		});

		assertThatCode(() -> trigger.afterTurn(UUID.randomUUID(), 9)).doesNotThrowAnyException();
	}
}

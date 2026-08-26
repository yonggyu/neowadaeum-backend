package com.neowadaeum.ai.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.neowadaeum.ai.provider.AiCallAttempt;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 기록기의 성질 (B-25).
 *
 * <p>여기서 보는 것은 <b>기록이 서비스를 붙잡지 않는가</b>다. 관측을 붙인 대가로 본문이 막히면
 * 관측을 떼게 되고, 그러면 §13-20 이 적은 공백이 그대로 돌아온다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class AiCallRecordingTests {

	private static final Clock FIXED = Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC);

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	@AfterEach
	void shutdown() {
		this.executor.shutdownNow();
	}

	private static AiCallLog.Draft draft(int attemptNo) {
		return new AiCallLog.Draft(UUID.randomUUID(), null, "turn", "anthropic", "claude-opus-5",
				null, "{}", "{}", 10, 20, 5, null, null, attemptNo);
	}

	/**
	 * <b>기록 실패가 호출자에게 새지 않는다</b> (B-25 DoD).
	 *
	 * <p>로그가 본문을 막으면 <b>관측을 붙인 대가로 서비스가 멈춘다.</b> 관측은 서비스보다 나중이다.
	 */
	@Test
	void B25_a_failing_repository_does_not_break_the_caller() throws InterruptedException {
		CountDownLatch attempted = new CountDownLatch(1);
		AiCallLogRepository failing = mock(AiCallLogRepository.class);
		given(failing.save(any(AiCallLog.class))).willAnswer(invocation -> {
			attempted.countDown();
			throw new IllegalStateException("promptlog is down");
		});

		AsyncAiCallRecorder recorder = new AsyncAiCallRecorder(failing, this.executor, FIXED);

		assertThatCode(() -> recorder.record(draft(1))).doesNotThrowAnyException();
		assertThat(attempted.await(5, TimeUnit.SECONDS)).as("저장을 시도조차 하지 않았다").isTrue();
	}

	/**
	 * <b>기록이 호출자의 스레드를 붙들지 않는다</b> (R4.6 과 같은 성질).
	 *
	 * <p>턴 응답은 이미 25초 예산 안에서 아슬아슬하다 (§13-19). DB 쓰기가 동기로 붙으면
	 * <b>관측을 붙인 만큼 지연이 늘어난다.</b>
	 */
	@Test
	void B25_recording_does_not_block_the_caller() throws InterruptedException {
		CountDownLatch saving = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AiCallLogRepository slow = mock(AiCallLogRepository.class);
		given(slow.save(any(AiCallLog.class))).willAnswer(invocation -> {
			saving.countDown();
			release.await(10, TimeUnit.SECONDS);
			return invocation.getArgument(0);
		});

		AsyncAiCallRecorder recorder = new AsyncAiCallRecorder(slow, this.executor, FIXED);

		long startedAt = System.nanoTime();
		recorder.record(draft(1));
		long elapsedMs = java.time.Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

		assertThat(saving.await(5, TimeUnit.SECONDS)).as("저장이 시작되지 않았다").isTrue();
		assertThat(elapsedMs)
				.as("record() 가 저장이 끝나기를 기다렸다 — 턴 지연에 그대로 더해진다")
				.isLessThan(1_000);
		release.countDown();
	}

	/**
	 * <b>재요청은 시도 번호가 갈린다</b> (R5.8 · R3.3).
	 *
	 * <p>한 턴에 몇 번 불렀는지가 곧 청구액이다. 번호가 갈리지 않으면 <b>재요청이 통계에서
	 * 사라진다.</b>
	 */
	@Test
	void R5_8_the_attempt_number_follows_the_retry_scope() {
		assertThat(AiCallAttempt.current()).as("데코레이터 밖에서는 1이다").isEqualTo(1);

		List<Integer> seen = List.of(
				AiCallAttempt.within(1, AiCallAttempt::current),
				AiCallAttempt.within(2, AiCallAttempt::current),
				AiCallAttempt.within(3, AiCallAttempt::current));

		assertThat(seen).containsExactly(1, 2, 3);
		assertThat(AiCallAttempt.current()).as("범위를 벗어나면 되돌아온다").isEqualTo(1);
	}
}

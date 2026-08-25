package com.neowadaeum.ai.provider;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Provider 호출에 시간 제한을 건다 (R6.4, §6.3).
 *
 * <p><b>제한을 넘기면 요청을 취소하고 {@link GenerationTimedOutException} 을 던진다.</b> 호출자는
 * 이것을 {@code 504 GENERATION_TIMEOUT} 으로 바꾸며, <b>세션은 직전 턴 상태 그대로 유지된다</b> —
 * §4.3 의 8단계(상태 병합) 이전에서 끊기기 때문이다 (R6.6).
 *
 * <p><b>데코레이터로 둔 이유.</b> 시간 제한은 어느 Provider 를 붙이든 같아야 하고(I-13 과 같은
 * 성질), 각 어댑터가 스스로 지키게 하면 새 어댑터가 그것을 잊는다. 감싸는 쪽에 두면 잊을 자리가 없다.
 *
 * <p><b>취소가 요점이다.</b> 기다리다 포기하는 것만으로는 뒤에서 계속 도는 호출이 남고, 그 비용은
 * 그대로 청구된다. {@link Future#cancel(boolean)} 으로 인터럽트를 보낸다.
 */
public class TimeLimitedStoryProvider implements StoryProvider {

	private final StoryProvider delegate;
	private final ExecutorService executor;
	private final Duration timeout;

	/**
	 * <b>제한 시간을 받지 않는 생성자를 두지 않는다</b> (#25).
	 *
	 * <p>기본값을 가진 생성자가 있으면 새 어댑터가 그것을 부르고, 설정으로 뺀 값이 조용히
	 * 무시된다. 값의 출처는 {@link ProviderProperties} 하나다.
	 */
	public TimeLimitedStoryProvider(StoryProvider delegate, ExecutorService executor, Duration timeout) {
		this.delegate = delegate;
		this.executor = executor;
		this.timeout = timeout;
	}

	@Override
	public String providerId() {
		return this.delegate.providerId();
	}

	@Override
	public TurnResult generateTurn(TurnRequest request) {
		Callable<TurnResult> call = () -> this.delegate.generateTurn(request);
		Future<TurnResult> pending = this.executor.submit(call);

		try {
			return pending.get(this.timeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException ex) {
			// 취소하지 않으면 뒤에서 계속 돌고 그 비용이 청구된다.
			pending.cancel(true);
			throw new GenerationTimedOutException(this.timeout);
		}
		catch (ExecutionException ex) {
			Throwable cause = ex.getCause();
			if (cause instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new IllegalStateException("provider call failed", cause);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			pending.cancel(true);
			throw new GenerationTimedOutException(this.timeout);
		}
	}

	/**
	 * 시간 제한 초과.
	 *
	 * <p>메시지에 <b>요청 내용을 담지 않는다</b> — 예외는 로그로 흐른다 (S-3).
	 */
	public static class GenerationTimedOutException extends RuntimeException {

		public GenerationTimedOutException(Duration timeout) {
			super("provider did not answer within " + timeout);
		}
	}
}

package com.neowadaeum.ai.provider;

import com.neowadaeum.play.port.GeneratedTurn;
import com.neowadaeum.play.port.GenerationTimedOutException;
import com.neowadaeum.play.port.TurnRequest;
import java.time.Clock;
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
 * <p><b>제한은 한 번의 호출이 아니라 생성 전체에 걸린다</b> (§13-19, B-21-2). 여기서
 * {@link GenerationBudget} 을 열고, 안쪽의 {@link SchemaRetryingStoryProvider} 는 그 예산을 보고
 * 재요청 여부를 정한다. <b>대기 상한도 그 deadline 에서 파생된다</b> — 예산과 대기가 서로 다른
 * 값에서 나오면 둘이 갈라지는 날을 아무도 모른다.
 *
 * <p><b>네 메서드 전부에 같은 제한이 걸린다</b> — {@code capabilities()} 만 예외이며 그것은 호출이
 * 아니라 상수 조회다. 하나라도 빠지면 그 경로가 제한 밖으로 나간다.
 */
public class TimeLimitedStoryProvider implements StoryProvider {

	private final StoryProvider delegate;
	private final ExecutorService executor;
	private final Duration timeout;
	private final Clock clock;

	/**
	 * <b>제한 시간을 받지 않는 생성자를 두지 않는다</b> (#25).
	 *
	 * <p>기본값을 가진 생성자가 있으면 새 어댑터가 그것을 부르고, 설정으로 뺀 값이 조용히
	 * 무시된다. 값의 출처는 {@link ProviderProperties} 하나다.
	 *
	 * <p><b>{@link Clock} 도 같은 이유로 받는다</b> (B-21-2). 안에서 현재 시각을 읽으면 남은 예산이
	 * 얼마일 때 무엇이 일어나는지를 실제 시간을 써야만 볼 수 있다.
	 */
	public TimeLimitedStoryProvider(StoryProvider delegate, ExecutorService executor, Duration timeout, Clock clock) {
		this.delegate = delegate;
		this.executor = executor;
		this.timeout = timeout;
		this.clock = clock;
	}

	@Override
	public String providerId() {
		return this.delegate.providerId();
	}

	/**
	 * <b>시간 제한을 걸지 않는다.</b> 능력 조회는 호출이 아니라 상수 조회다 — 여기에 실행기를 태우면
	 * 스레드만 쓰고 얻는 것이 없다.
	 */
	@Override
	public ProviderCapabilities capabilities() {
		return this.delegate.capabilities();
	}

	@Override
	public GeneratedTurn generateTurn(TurnRequest request) {
		return withinLimit(() -> this.delegate.generateTurn(request));
	}

	/**
	 * 요약도 같은 제한을 받는다.
	 *
	 * <p>턴 응답 이후 비동기로 도는 호출이지만(R4.6) <b>비용은 같은 곳에서 나간다.</b> 사용자가
	 * 기다리지 않는다는 이유로 무한정 도는 호출을 남겨 두지 않는다.
	 */
	@Override
	public String summarize(SummaryRequest request) {
		return withinLimit(() -> this.delegate.summarize(request));
	}

	@Override
	public OutlineResult draftOutline(OutlineRequest request) {
		return withinLimit(() -> this.delegate.draftOutline(request));
	}

	/**
	 * 예산을 열고 그 안에서 위임을 실행한다.
	 *
	 * <p><b>취소가 요점이다.</b> 기다리다 포기하는 것만으로는 뒤에서 계속 도는 호출이 남고, 그 비용은
	 * 그대로 청구된다. {@link Future#cancel(boolean)} 으로 인터럽트를 보낸다.
	 *
	 * <p><b>예산은 실행기 스레드 안에서 열린다.</b> 재요청과 어댑터가 도는 곳이 거기이므로 예산도
	 * 거기 있어야 한다. deadline 자체는 <b>제출 전에</b> 정해져 값으로 넘어간다 — 스레드가 언제
	 * 시작되는지에 예산의 시작점이 흔들리지 않아야 한다.
	 */
	private <T> T withinLimit(Callable<T> call) {
		GenerationBudget budget = GenerationBudget.startingNow(this.clock, this.timeout);
		Future<T> pending = this.executor.submit(() -> GenerationBudget.within(budget, call));

		try {
			return pending.get(budget.remaining().toMillis(), TimeUnit.MILLISECONDS);
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
}

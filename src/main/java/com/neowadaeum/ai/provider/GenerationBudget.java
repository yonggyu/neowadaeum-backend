package com.neowadaeum.ai.provider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;

/**
 * 한 번의 턴 생성에 허용된 시간과 그 소진 상태 (R6.4, §6.3, §13-19, B-21-2).
 *
 * <p><b>왜 값이어야 하는가.</b> §13-19 는 <b>재요청을 포함한 전체 생성</b>을 25초로 정했다. 그런데
 * 그 결정을 강제하던 것은 데코레이터의 중첩 순서 하나였다 —
 * {@code TimeLimited(SchemaRetrying(adapter))} 가 {@code SchemaRetrying(TimeLimited(adapter))} 로
 * 뒤집히면 <b>예산이 조용히 두 배가 된다.</b> 두 배선은 타입이 같고, 스키마 위반만 재요청하므로
 * <b>느리기만 한 어댑터는 양쪽에서 똑같이 504 를 낸다</b> — 컴파일도 단위 테스트도 그 사고를 잡지
 * 못한다. 예산을 값으로 만들면 그 사고가 <b>첫 호출에서 즉시</b> 드러난다.
 *
 * <p><b>스레드에 매다는 근거는 {@link AiCallAttempt} 와 같다.</b> 예산을 아는 것은
 * {@link TimeLimitedStoryProvider} 이고 <b>재요청 여부를 정하는 것은</b>
 * {@link SchemaRetryingStoryProvider} 인데, 둘은 {@link StoryProvider} 시그니처로만 이어져 있어
 * 값을 넘길 자리가 없다. 시간 제한이 만든 실행기 스레드 <b>안에서</b> 재요청과 어댑터가 함께 도므로
 * 그 스레드에 매달면 닿는다.
 *
 * <p><b>{@link Clock} 을 받는다.</b> {@code Instant.now()} 를 안에서 부르면 "남은 예산이 3초일 때
 * 재요청이 어떻게 되는가" 를 실제로 3초를 기다려야만 볼 수 있다. 시계를 밖에서 주면 테스트가 시간을
 * 앞으로 밀어 {@code sleep} 없이 같은 경로를 지난다 (I-15 의 결정론과 같은 성질).
 */
public final class GenerationBudget {

	private static final ThreadLocal<GenerationBudget> CURRENT = new ThreadLocal<>();

	private final Clock clock;

	private final Duration total;

	private final Instant deadline;

	private GenerationBudget(Clock clock, Duration total, Instant deadline) {
		this.clock = clock;
		this.total = total;
		this.deadline = deadline;
	}

	/** 지금을 기준으로 {@code total} 만큼의 예산을 연다. 이 시점이 deadline 을 정한다. */
	public static GenerationBudget startingNow(Clock clock, Duration total) {
		return new GenerationBudget(clock, total, clock.instant().plus(total));
	}

	/**
	 * 이 예산 안에서 {@code body} 를 실행한다. 끝나면 이전 값으로 되돌린다.
	 *
	 * <p>중첩을 금지하지 않고 복원한다 — 금지하면 <b>테스트가 감싸는 방식</b>까지 규칙이 되고, 그
	 * 규칙은 운영 배선과 무관하다.
	 */
	public static <T> T within(GenerationBudget budget, Callable<T> body) throws Exception {
		GenerationBudget previous = CURRENT.get();
		CURRENT.set(budget);
		try {
			return body.call();
		}
		finally {
			if (previous == null) {
				CURRENT.remove();
			}
			else {
				CURRENT.set(previous);
			}
		}
	}

	/**
	 * 지금 열려 있는 예산.
	 *
	 * <p><b>없으면 실패한다.</b> 기본값을 만들어 주면 예산 밖에서 도는 호출이 <b>정상으로 보이고</b>,
	 * 그것이 바로 이 작업이 없애려는 상태다 — 지켜야 할 규칙이 있는데 지키는지 확인할 수 없는 상태.
	 * 배선이 뒤집히면 여기서 즉시 드러난다.
	 */
	public static GenerationBudget current() {
		GenerationBudget budget = CURRENT.get();
		if (budget == null) {
			throw new IllegalStateException("no generation budget is open — "
					+ "the retry decorator must run inside the time limit (see 13-19)");
		}
		return budget;
	}

	/** 남은 시간. 이미 지났으면 {@link Duration#ZERO} 다 — 음수 예산을 호출 상한으로 넘기지 않는다. */
	public Duration remaining() {
		Duration left = Duration.between(this.clock.instant(), this.deadline);
		return left.isNegative() ? Duration.ZERO : left;
	}

	/** 예산이 남지 않았는가. 남지 않았으면 <b>다음 호출을 걸지 않는다.</b> */
	public boolean exhausted() {
		return remaining().isZero();
	}

	/** 처음에 주어진 전체 예산. 초과를 알릴 때 무엇을 넘겼는지 가리키는 값이다. */
	public Duration total() {
		return this.total;
	}
}

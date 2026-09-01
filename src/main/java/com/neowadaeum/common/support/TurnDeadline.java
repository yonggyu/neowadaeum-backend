package com.neowadaeum.common.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * 턴 하나에 허용된 전체 시간 (§6.3, R6.4, #116).
 *
 * <p><b>{@code GenerationBudget}(#87) 과 무엇이 다른가.</b> 그것의 단위는 <b>한 번의 생성 호출</b>이고
 * 재요청까지가 그 안이다. 그러나 B-30 이 판정을 붙이면서 한 턴의 외부 호출이 최대 넷이 됐다 —
 * 생성 · 판정 · 재생성 · 재판정. <b>각각에 상한이 있어도 넷을 합친 값에는 상한이 없었다.</b>
 * 산술적으로 §6.3 의 28초를 넘길 수 있고, 그때 클라이언트는 이미 끊은 뒤이며 그 뒤의 호출 비용은
 * <b>아무도 받지 못한 응답에 청구된다.</b>
 *
 * <p>그래서 <b>턴 하나에 deadline 을 하나 정하고</b> 그 아래의 모든 외부 호출이 남은 예산을 나눠
 * 쓰게 한다. 각 호출의 상한은 {@code min(자기 상한, 턴에 남은 시간)} 이다.
 *
 * <p><b>스레드에 매다는 근거는 {@code GenerationBudget} 과 같다.</b> 예산을 여는 것은 {@code play}
 * 의 파이프라인이고 그것을 읽는 것은 {@code ai} 의 시간 제한 데코레이터인데, 둘은 포트
 * 시그니처로만 이어져 있어 값을 넘길 자리가 없다 (ADR-0006 — {@code play → ai} 는 금지다).
 *
 * <p><b>열려 있지 않으면 실패하지 않는다.</b> {@code GenerationBudget} 과 반대다 — 요약(R4.6)처럼
 * 턴 응답 <b>이후</b>에 도는 호출은 사용자를 기다리게 하지 않으므로 턴 예산에 묶일 이유가 없다.
 * 그 경우 각 호출은 자기 상한만 지킨다.
 *
 * <p><b>{@link Clock} 을 받는다.</b> 남은 예산이 3초일 때 무엇이 일어나는지를 실제로 3초를 기다려야
 * 볼 수 있으면 그 경로는 검증되지 않는다.
 */
public final class TurnDeadline {

	private static final ThreadLocal<TurnDeadline> CURRENT = new ThreadLocal<>();

	private final Clock clock;

	private final Duration total;

	private final Instant deadline;

	private TurnDeadline(Clock clock, Duration total, Instant deadline) {
		this.clock = clock;
		this.total = total;
		this.deadline = deadline;
	}

	/** 지금을 기준으로 {@code total} 만큼의 예산을 연다. 이 시점이 deadline 을 정한다. */
	public static TurnDeadline startingNow(Clock clock, Duration total) {
		return new TurnDeadline(clock, total, clock.instant().plus(total));
	}

	/** 이 예산 안에서 {@code body} 를 실행한다. 끝나면 이전 값으로 되돌린다. */
	public static <T> T within(TurnDeadline deadline, Supplier<T> body) {
		TurnDeadline previous = CURRENT.get();
		CURRENT.set(deadline);
		try {
			return body.get();
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
	 * 이 호출에 실제로 허용되는 시간.
	 *
	 * <p>턴 예산이 열려 있지 않으면 {@code ownLimit} 그대로다. 열려 있으면 <b>둘 중 작은 쪽</b>이며,
	 * 그래서 앞 단계가 예산의 대부분을 쓰면 뒤 단계에 남는 시간이 그만큼 줄어든다.
	 */
	public static Duration allowedFor(Duration ownLimit) {
		TurnDeadline current = CURRENT.get();
		if (current == null) {
			return ownLimit;
		}
		Duration left = current.remaining();
		return left.compareTo(ownLimit) < 0 ? left : ownLimit;
	}

	/**
	 * 턴 예산이 열려 있고 <b>다 썼는가</b>.
	 *
	 * <p>열려 있지 않으면 {@code false} 다 — 예산이 없는 것과 예산이 소진된 것은 다르다.
	 */
	public static boolean exhausted() {
		TurnDeadline current = CURRENT.get();
		return current != null && current.remaining().isZero();
	}

	/** 열려 있는 예산의 전체 크기. 초과를 알릴 때 무엇을 넘겼는지 가리킨다. */
	public static Duration currentTotal(Duration fallback) {
		TurnDeadline current = CURRENT.get();
		return (current != null) ? current.total() : fallback;
	}

	/** 남은 시간. 이미 지났으면 {@link Duration#ZERO} 다 — 음수를 상한으로 넘기지 않는다. */
	public Duration remaining() {
		Duration left = Duration.between(this.clock.instant(), this.deadline);
		return left.isNegative() ? Duration.ZERO : left;
	}

	/** 처음에 주어진 전체 예산. */
	public Duration total() {
		return this.total;
	}
}

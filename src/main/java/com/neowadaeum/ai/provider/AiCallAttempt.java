package com.neowadaeum.ai.provider;

/**
 * 지금이 몇 번째 시도인가 (B-25, R5.8 · R3.3).
 *
 * <p><b>왜 이런 것이 필요한가.</b> 재요청은 <b>같은 턴의 별도 호출</b>이며 {@code ai_call_log} 에
 * 별개 행으로 남아야 한다 — 그래야 "한 턴에 몇 번 불렀는가"가 보이고, 그 숫자가 곧 청구액이다.
 * 그런데 횟수를 아는 것은 {@link SchemaRetryingStoryProvider} 이고 <b>원문을 아는 것은
 * 어댑터</b>다. 둘은 포트 시그니처로 이어져 있어 값을 넘길 자리가 없다.
 *
 * <p><b>스레드에 매다는 것이 성립하는 근거.</b> 배선이
 * {@code AiGateway(TimeLimited(SchemaRetrying(adapter)))} 이므로 (§13-19), 시간 제한이 만든
 * 실행기 스레드 <b>안에서</b> 재요청과 어댑터가 함께 돈다. 그 관계가 깨지면
 * {@code AiGatewayWiringTests} 가 먼저 실패한다.
 *
 * <p><b>설정되지 않았으면 1이다.</b> 데코레이터를 거치지 않고 어댑터를 직접 부르는 경로(테스트)가
 * 있고, 거기서 0 이나 예외가 나오면 기록이 아니라 호출이 깨진다.
 */
public final class AiCallAttempt {

	private static final ThreadLocal<Integer> CURRENT = new ThreadLocal<>();

	private AiCallAttempt() {
	}

	/** 이 시도 번호로 {@code body} 를 실행한다. 끝나면 이전 값으로 되돌린다. */
	public static <T> T within(int attemptNo, java.util.function.Supplier<T> body) {
		Integer previous = CURRENT.get();
		CURRENT.set(attemptNo);
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

	/** 현재 시도 번호. 설정되지 않았으면 {@code 1} 이다. */
	public static int current() {
		Integer attempt = CURRENT.get();
		return (attempt != null) ? attempt : 1;
	}
}

package com.neowadaeum.ai.provider;

import java.util.function.Supplier;

/**
 * 이 호출이 <b>승계된 것</b>인가, 그렇다면 원래 누구였나 (R3.7, B-23).
 *
 * <p>{@link AiCallAttempt} 와 같은 구조이며 이유도 같다 — <b>승계를 아는 것은 체인</b>이고
 * <b>기록을 만드는 것은 어댑터</b>인데, 둘은 포트 시그니처로 이어져 있어 값을 넘길 자리가 없다.
 *
 * <p><b>스레드에 매다는 것이 성립하는 근거도 같다.</b> 체인은 게이트웨이 안쪽, 시간 제한이 만든
 * 실행기 스레드에서 어댑터와 함께 돈다.
 *
 * <p>설정되지 않았으면 {@code null} 이다 — 승계가 없었다는 뜻이며, 그것이 정상 경로다.
 */
public final class AiCallFallback {

	private static final ThreadLocal<String> INTENDED = new ThreadLocal<>();

	private AiCallFallback() {
	}

	/** 원래 지목됐던 provider 를 걸고 {@code body} 를 실행한다. */
	public static <T> T within(String intendedProviderId, Supplier<T> body) {
		String previous = INTENDED.get();
		INTENDED.set(intendedProviderId);
		try {
			return body.get();
		}
		finally {
			if (previous == null) {
				INTENDED.remove();
			}
			else {
				INTENDED.set(previous);
			}
		}
	}

	/** 승계였다면 원래 provider, 아니면 {@code null}. */
	public static String intendedProviderId() {
		return INTENDED.get();
	}
}

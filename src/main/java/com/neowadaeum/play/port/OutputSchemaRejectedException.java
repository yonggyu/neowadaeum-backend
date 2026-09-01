package com.neowadaeum.play.port;

/**
 * 허용된 재요청을 다 쓰고도 출력 스키마를 만족하지 못했다 (R5.8, R3.3).
 *
 * <p>호출자는 이것을 {@code 502 PROVIDER_ERROR} 로 바꾼다. 시간 초과와 같은 자리에서 끊기므로
 * 세션 상태는 그대로다 (R6.6).
 *
 * <p><b>포트 패키지에 사는 이유는 {@link GenerationTimedOutException} 과 같다</b> (ADR-0006).
 *
 * <p><b>메시지에 응답 원문이 없다</b> (S-3). 원인 예외도 어긋난 지점까지만 담는다.
 */
public class OutputSchemaRejectedException extends RuntimeException {

	public OutputSchemaRejectedException(int attempts, Throwable lastViolation) {
		super("provider output did not match the turn schema in " + attempts + " attempts", lastViolation);
	}
}

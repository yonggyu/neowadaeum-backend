package com.neowadaeum.play.port;

/**
 * Provider 호출 자체가 실패했다 — 연결 · 인증 · 4xx · 5xx (B-22).
 *
 * <p><b>스키마 위반과 구분한다.</b> {@link OutputSchemaRejectedException} 은 다시 요청해 볼 만하지만
 * (R5.8), 인증 실패나 연결 실패는 같은 요청을 다시 보내 나아지지 않는다 — 재요청은 비용만 쓴다.
 *
 * <p>호출자는 이것을 {@code 502 PROVIDER_ERROR} 로 바꾼다. 시간 초과와 같은 자리에서 끊기므로
 * 세션 상태는 그대로다 (R6.6).
 *
 * <p><b>포트 패키지에 사는 이유는 다른 두 예외와 같다</b> (ADR-0006). {@code play} 가 어댑터의
 * 타입을 잡아야 한다면 그 순간 의존이 양방향이 된다.
 *
 * <p><b>벤더별로 나누지 않는다.</b> {@code play} 는 어느 벤더가 실패했는지로 분기하지 않는다 —
 * 응답은 어느 쪽이든 502 다. 벤더를 구분해야 하는 것은 fallback 체인(B-23)이고 그것은
 * {@code ai} 안쪽의 관심사다.
 *
 * <p><b>메시지에 응답 본문도 API 키도 없다</b> (S-3). 에러 응답이 키를 되비쳐 오는 경우가 있고,
 * 예외는 로그로 흐른다.
 */
public class ProviderCallFailedException extends RuntimeException {

	public ProviderCallFailedException(String message) {
		super(message);
	}
}

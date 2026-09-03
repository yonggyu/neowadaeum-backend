package com.neowadaeum.common.spi;

/**
 * 표시명이 규칙에 맞지 않는다 (#271, #287, §13-7).
 *
 * <p><b>왜 전용 예외인가.</b> 규칙의 정본은 catalog 도메인에 있고 요청을 받는 것은 identity 다.
 * identity 는 catalog 를 볼 수 없으므로(허용 의존이 {@code common} 하나다) 거절 사유를 <b>경계를
 * 넘어 전달할 타입</b>이 필요하다. {@code IllegalArgumentException} 을 그대로 잡으면 <b>구현의
 * 버그까지 400 으로 바뀐다</b> — 잘못 넘긴 인자와 사용자가 잘못 쓴 이름이 같은 응답을 받는다.
 * {@link SafetyClassificationFailedException} · {@link OutlineDraftFailedException} 과 같은 자리다.
 *
 * <p><b>{@link #reason()} 에 사용자가 보낸 값을 담지 않는다</b> (S-3). 거절된 입력이 응답으로
 * 되돌아오면 그 값은 클라이언트 로그와 에러 리포트로 퍼진다 — 어떤 규칙을 어겼는지만 말한다.
 */
public class InvalidDisplayNameException extends RuntimeException {

	private final String reason;

	/**
	 * @param reason 어긴 규칙. <b>입력값을 포함하지 않는 고정 문구여야 한다</b>
	 */
	public InvalidDisplayNameException(String reason, Throwable cause) {
		super(reason, cause);
		this.reason = reason;
	}

	public String reason() {
		return this.reason;
	}
}

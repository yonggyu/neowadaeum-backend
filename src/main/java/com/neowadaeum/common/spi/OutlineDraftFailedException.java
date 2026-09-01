package com.neowadaeum.common.spi;

/**
 * 초안을 만들지 못했다 (B-52).
 *
 * <p><b>{@link SafetyClassificationFailedException} 과 같은 자리에 있다.</b> {@code authoring} 은
 * {@code ai} 를 보지 않으므로(§5.4) Provider 쪽 예외를 직접 잡을 수 없다 — 그 경계를 넘어오는
 * 실패에는 <b>SPI 가 소유하는 이름</b>이 필요하다.
 *
 * <p><b>실패를 감추지 않는다.</b> {@link OutlineDrafter} 가 이미 못박은 것과 같다 — 빈 초안을
 * 돌려주면 작성자는 <b>AI 가 아무 생각이 없었다</b>고 읽는다.
 *
 * <p><b>호출 실패·시간 초과·계약 위반을 하나로 묶는다.</b> 작성자가 할 수 있는 일이 셋 다
 * <b>다시 누르는 것</b> 하나뿐이라, 셋을 가르는 이름은 화면에서 값을 하지 않는다. 무엇이었는지는
 * {@code ai_call_log} 와 구조화 로그가 갖는다. 턴 경로가 시간 초과(504)와 호출 실패(502)를
 * 가르는 것은 <b>세션 상태를 말해야 하기 때문</b>인데(R6.6), 초안에는 지킬 세션이 없다.
 */
public class OutlineDraftFailedException extends RuntimeException {

	public OutlineDraftFailedException(String message, Throwable cause) {
		super(message, cause);
	}
}

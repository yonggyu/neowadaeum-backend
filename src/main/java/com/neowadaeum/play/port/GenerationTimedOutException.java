package com.neowadaeum.play.port;

import java.time.Duration;

/**
 * 생성 예산 초과 (R6.4, §6.3).
 *
 * <p>호출자는 이것을 {@code 504 GENERATION_TIMEOUT} 으로 바꾸며, <b>세션은 직전 턴 상태 그대로
 * 유지된다</b> — §6.1 의 7단계(상태 병합) 이전에서 끊기기 때문이다 (R6.6).
 *
 * <p><b>포트 패키지에 사는 이유</b> (ADR-0006). 이전에는 {@code ai} 의 데코레이터 안에 중첩
 * 클래스로 있었고, 그것을 잡으려면 {@code play} 가 {@code ai} 를 참조해야 했다. <b>예외 하나
 * 때문에 의존이 양방향이 된다.</b>
 *
 * <p><b>메시지에 요청 내용을 담지 않는다</b> — 예외는 로그로 흐른다 (S-3).
 */
public class GenerationTimedOutException extends RuntimeException {

	public GenerationTimedOutException(Duration budget) {
		super("turn generation did not finish within " + budget);
	}
}

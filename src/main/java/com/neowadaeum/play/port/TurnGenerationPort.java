package com.neowadaeum.play.port;

/**
 * 턴 생성 — <b>{@code play} 가 필요로 하는 것의 선언</b> (ADR-0006, #84).
 *
 * <p><b>이것은 {@code ai} 가 제공하는 API 가 아니라 {@code play} 가 내미는 요구다.</b> 구현은
 * {@code ai} 가 하고 배선은 그쪽이 하지만, <b>모양을 정하는 것은 부르는 쪽</b>이다 — 그래야
 * {@link GeneratedTurn} 이 저장·응답할 형태에서 나온다.
 *
 * <p><b>메서드가 둘뿐인 것이 요점이다.</b> {@code play} 가 실제로 쓰는 것이 그 둘이다. 요약
 * (B-34) · 아웃라인(B-52) · 능력 조회는 {@code ai} 내부의 관심사이며, <b>지금 필요 없는 것을
 * 포트에 미리 얹지 않는다</b> — 얹으면 구현하지 않는 메서드가 생기고 그 자리에 "일단 통과시키는"
 * 구현이 들어간다 (§0.2).
 *
 * <p><b>실패는 이 패키지의 예외로 나온다.</b> {@link GenerationTimedOutException} ·
 * {@link OutputSchemaRejectedException} — 호출자가 {@code ai} 의 타입을 잡아야 한다면 그 순간
 * {@code play → ai} 간선이 되살아나고 의존이 양방향이 된다 (ADR-0006).
 */
public interface TurnGenerationPort {

	/**
	 * 세션에 고정할 Provider 식별자 (I-4, R3.5).
	 *
	 * <p>세션 생성 시 한 번 읽어 저장한다. 중간에 바뀌면 I-4 위반이다.
	 */
	String providerId();

	/**
	 * 다음 턴을 생성한다 (§6.1-4, §6.1-5).
	 *
	 * @throws GenerationTimedOutException   생성 예산을 넘겼다 (R6.4). 세션 상태는 그대로다 (R6.6)
	 * @throws OutputSchemaRejectedException 재요청까지 출력 스키마를 만족하지 못했다 (R5.8, R3.3)
	 */
	GeneratedTurn generateTurn(TurnRequest request);
}

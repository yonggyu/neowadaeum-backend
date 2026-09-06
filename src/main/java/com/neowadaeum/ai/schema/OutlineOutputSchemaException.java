package com.neowadaeum.ai.schema;

/**
 * 응답이 초안 출력 계약이 아니다 (B-52).
 *
 * <p><b>{@link TurnOutputSchemaException} 과 형제이지 같은 것이 아니다.</b> 저쪽은 §5.2 의 턴
 * 스키마를 가리키고 이쪽은 초안 계약을 가리킨다. 하나로 합치면 재요청 규칙도 하나가 되는데,
 * 둘은 규칙이 다르다 — 턴은 Provider 능력에 따라 1~2회이고 (R5.8 · R3.3) 초안은 1회다.
 *
 * <p><b>{@code ai} 모듈 내부 예외다.</b> 이것을 잡는 것은 재요청 데코레이터
 * ({@code SchemaRetryingStoryProvider}) 이며, 재요청까지 실패했을 때 모듈 밖으로 나가는 것은
 * {@code common/spi} 의 seam 예외다.
 *
 * <p><b>S-3 — 메시지에 응답 원문을 담지 않는다.</b> 원문 보관은 {@code ai_call_log} 의 일이다.
 * 여기 남기는 것은 <b>무엇이 어긋났는지</b>까지다.
 *
 * <p><b>원인 예외도 붙이지 않는다</b> (§13-86). 이 예외를 만드는 자리는 JSON 파서의 실패를 받는
 * 곳이고, 파서의 메시지는 <b>어긋난 지점을 보이려고 원문 조각을 인용한다</b> — 메시지만 검사하는
 * 단언은 그것을 잡지 못한 채 통과한다. 남기는 것은 {@code ProviderCallFailedException} 과 같은
 * <b>타입 이름의 사슬</b>뿐이다.
 */
public class OutlineOutputSchemaException extends RuntimeException {

	public OutlineOutputSchemaException(String message) {
		super(message);
	}

	/**
	 * @param causeChain {@link com.neowadaeum.play.port.ProviderCallFailedException#typeChainOf}
	 *     이 만든 타입 이름의 사슬. {@code Throwable} 을 받지 않는 것이 S-3 의 구조적 보장이다
	 */
	public OutlineOutputSchemaException(String message, String causeChain) {
		super(message + " cause=" + causeChain);
	}
}

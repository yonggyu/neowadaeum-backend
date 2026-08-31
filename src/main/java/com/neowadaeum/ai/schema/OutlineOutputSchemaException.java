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
 */
public class OutlineOutputSchemaException extends RuntimeException {

	public OutlineOutputSchemaException(String message) {
		super(message);
	}

	public OutlineOutputSchemaException(String message, Throwable cause) {
		super(message, cause);
	}
}

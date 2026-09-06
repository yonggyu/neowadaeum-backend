package com.neowadaeum.ai.schema;

/**
 * 응답이 §5.2 의 출력 스키마가 아니다 (B-21).
 *
 * <p><b>{@code ai} 모듈 내부 예외다.</b> 이것을 잡는 것은 재요청 데코레이터
 * ({@code SchemaRetryingStoryProvider}) 이고, 재요청까지 실패했을 때 밖으로 나가는 것은
 * 그쪽의 seam 예외다 — {@code play} 가 {@code ai :: provider} 밖을 참조하면 §5.4 위반이다.
 *
 * <p><b>S-3 — 메시지에 응답 원문을 담지 않는다.</b> 예외는 로그로 흐르고, 응답 원문은
 * {@code ai_call_log}(별도 스토어)만 보관한다. 여기 남기는 것은 <b>무엇이 어긋났는지</b>까지다 —
 * 스키마를 못 맞춘 모델을 고치는 데 필요한 것은 값이 아니라 어긋난 지점이다.
 *
 * <p><b>원인 예외를 받는 생성자를 두지 않는다</b> (§13-86). {@code TurnOutputParser} 는 처음부터
 * 파서 예외를 붙이지 않았지만 그것은 <b>규약이었을 뿐</b>이라, 생성자가 열려 있는 한 다음 사람이
 * 붙일 수 있었다. 형제인 {@code OutlineOutputSchemaException} 에서 실제로 그 일이 일어났다 —
 * 남길 것이 있으면 <b>타입 이름의 사슬</b>을 메시지에 담는다.
 */
public class TurnOutputSchemaException extends RuntimeException {

	public TurnOutputSchemaException(String message) {
		super(message);
	}

	/**
	 * @param causeChain {@link com.neowadaeum.play.port.ProviderCallFailedException#typeChainOf}
	 *     이 만든 타입 이름의 사슬. {@code Throwable} 을 받지 않는 것이 S-3 의 구조적 보장이다
	 */
	public TurnOutputSchemaException(String message, String causeChain) {
		super(message + " cause=" + causeChain);
	}
}

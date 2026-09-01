package com.neowadaeum.common.error;

import java.util.Map;

/**
 * §11 카탈로그의 에러를 클라이언트에게 내보내기 위한 애플리케이션 예외.
 *
 * <p>도메인·서비스 계층은 HTTP 를 알지 못한 채 이 예외만 던지고, 상태 코드와 본문 구성은
 * {@link GlobalExceptionHandler} 가 전담한다.
 *
 * <p><b>응답 메시지는 {@link ErrorCode#defaultMessage()} 로 고정된다.</b> 호출부가 문구를 갈아끼울 수 없게
 * 막아 둔 것이며, 이것이 내부 사정이 응답으로 새는 경로를 구조적으로 없앤다(S-6). 진단용 문맥은
 * {@link #getMessage()}(로그 전용)와 {@code details}(응답 노출)로 나눠 담는다.
 *
 * <p>{@code details} 에 담는 값은 <b>클라이언트가 봐도 안전한 것</b>만이다. 세이프티 차단 사유(R9.6),
 * 거절된 입력값 원문(S-3), 내부 식별자를 담지 않는다.
 */
public class ApiException extends RuntimeException {

	private final ErrorCode errorCode;
	private final Map<String, Object> details;

	public ApiException(ErrorCode errorCode) {
		this(errorCode, Map.of(), null);
	}

	public ApiException(ErrorCode errorCode, Map<String, Object> details) {
		this(errorCode, details, null);
	}

	public ApiException(ErrorCode errorCode, Throwable cause) {
		this(errorCode, Map.of(), cause);
	}

	public ApiException(ErrorCode errorCode, Map<String, Object> details, Throwable cause) {
		super(errorCode.code(), cause);
		this.errorCode = errorCode;
		this.details = details == null ? Map.of() : Map.copyOf(details);
	}

	public ErrorCode errorCode() {
		return errorCode;
	}

	public Map<String, Object> details() {
		return details;
	}
}

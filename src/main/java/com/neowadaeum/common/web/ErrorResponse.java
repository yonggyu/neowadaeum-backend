package com.neowadaeum.common.web;

import com.neowadaeum.common.error.ErrorCode;
import java.util.Map;

/**
 * §9.1 공통 에러 응답. 서버가 내보내는 <b>모든</b> 에러는 이 형태 하나로 수렴한다.
 *
 * <pre>{@code
 * { "error": "TURN_CONFLICT", "message": "...", "details": { } }
 * }</pre>
 *
 * <p>{@code details} 는 절대 {@code null} 이 되지 않는다. 값이 없으면 빈 객체다 — 프론트가 키 존재 여부로
 * 분기하지 않게 한다(§9.3).
 *
 * <p><b>여기에 담을 수 없는 것</b>: 스택트레이스, 예외 클래스명, SQL, 내부 경로, 세이프티 차단 사유,
 * 거절된 입력값 원문 (S-6, R9.6, S-3).
 *
 * @param error   {@link ErrorCode} 이름. 클라이언트는 이 값으로 문구를 매핑한다
 * @param message 안전한 폴백 문구. UI 에 그대로 쓰인다고 가정하지 않는다
 * @param details 코드별 부가 정보(예: {@code retryAfterSeconds}, {@code fields})
 */
public record ErrorResponse(String error, String message, Map<String, Object> details) {

	public ErrorResponse {
		details = details == null ? Map.of() : Map.copyOf(details);
	}

	public static ErrorResponse of(ErrorCode errorCode) {
		return new ErrorResponse(errorCode.code(), errorCode.defaultMessage(), Map.of());
	}

	public static ErrorResponse of(ErrorCode errorCode, Map<String, Object> details) {
		return new ErrorResponse(errorCode.code(), errorCode.defaultMessage(), details);
	}
}

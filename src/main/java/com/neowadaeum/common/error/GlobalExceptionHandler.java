package com.neowadaeum.common.error;

import com.neowadaeum.common.web.ErrorResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;

/**
 * 모든 에러 응답의 단일 출구. §9.1 형태 {@code {error, message, details}} 하나로 수렴시킨다.
 *
 * <p><b>S-6</b>: 스택트레이스·SQL·내부 경로·예외 클래스명을 응답에 노출하지 않는다. 진단 정보는 응답이 아니라
 * 구조화 로그(§9.4)로 보낸다. 요청 단위 추적은 {@code requestId}(MDC, §12 B-03)로 잇는다.
 *
 * <p>{@link ResponseEntityExceptionHandler} 를 상속해 Spring MVC 가 자체적으로 던지는 예외
 * (지원하지 않는 메서드·미디어 타입, 잘못된 JSON, 누락된 파라미터 등)까지 같은 형태로 바꾼다.
 * 이때 <b>HTTP 상태는 Spring 이 정한 값을 그대로 유지</b>하고 본문의 {@code error} 만 §11 카탈로그로 사상한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	/**
	 * 역직렬화 실패의 고정 사유. 어긋난 <b>자리</b>만 알리고 무엇이 어떻게 어긋났는지는 말하지 않는다.
	 *
	 * <p>여기에 보낸 값이나 기대 타입을 적으면 응답이 곧 입력의 메아리가 된다(S-3).
	 */
	private static final String MALFORMED_FIELD_REASON = "형식이 올바르지 않아요.";

	/** 응답에 실어도 되는 필드 이름의 모양. 이 밖의 문자가 섞이면 그 경로는 통째로 버린다. */
	private static final Pattern SAFE_PROPERTY_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");

	/** 경로 깊이 상한. 중첩이 이보다 깊으면 자리를 특정할 실익이 없다. */
	private static final int MAX_PATH_DEPTH = 8;

	/** §11 카탈로그에 대응하는 애플리케이션 예외. */
	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ErrorResponse> handleApiException(ApiException ex) {
		ErrorCode errorCode = ex.errorCode();
		logByStatus(errorCode, errorCode.status(), ex);
		return ResponseEntity.status(errorCode.status()).body(ErrorResponse.of(errorCode, ex.details()));
	}

	/**
	 * 인증·인가 예외는 여기서 처리하지 않고 Security 필터 체인으로 되던진다.
	 *
	 * <p>{@code @ExceptionHandler(Exception.class)} 폴백이 이 둘을 삼키면 익명 사용자에게 401 대신 403 이
	 * 나가고, {@code AuthenticationEntryPoint} 가 동작할 기회를 잃는다. 실제 응답 본문 구성은 B-12 에서
	 * {@code AuthenticationEntryPoint} / {@code AccessDeniedHandler} 에 {@link ErrorResponse} 를 물려 완성한다.
	 */
	@ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
	public void rethrowToSecurityFilterChain(RuntimeException ex) {
		throw ex;
	}

	/**
	 * 예상하지 못한 예외의 마지막 방어선.
	 *
	 * <p>여기까지 온 것은 내부 결함이다. 예외 정보는 로그에만 남기고 응답에는 {@link ErrorCode#INTERNAL_ERROR}
	 * 의 고정 문구만 내보낸다(S-6).
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
		log.error("api.error code={} status={}", ErrorCode.INTERNAL_ERROR.code(),
				ErrorCode.INTERNAL_ERROR.status().value(), ex);
		return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
				.body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR));
	}

	/**
	 * {@code @Valid} 본문 검증 실패 → 400 {@code VALIDATION_ERROR}.
	 *
	 * <p>필드명과 위반 사유만 담고 <b>거절된 입력값 원문은 담지 않는다.</b> 사용자가 쓴 문장이 그대로 되돌아오면
	 * 개인정보·UGC 원문이 응답과 클라이언트 로그로 퍼진다(S-3, S-7).
	 */
	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {

		List<Map<String, String>> fields = new ArrayList<>();
		for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
			fields.add(Map.of("field", fieldError.getField(), "reason", reasonOf(fieldError.getDefaultMessage())));
		}
		ex.getBindingResult().getGlobalErrors().forEach(globalError ->
				fields.add(Map.of("field", globalError.getObjectName(), "reason", reasonOf(globalError.getDefaultMessage()))));

		return validationFailure(fields, ex);
	}

	/** 파라미터·경로변수 검증 실패({@code @Validated}) → 400 {@code VALIDATION_ERROR}. */
	@Override
	protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {

		List<Map<String, String>> fields = new ArrayList<>();
		ex.getParameterValidationResults().forEach(result -> {
			String name = result.getMethodParameter().getParameterName();
			result.getResolvableErrors().forEach(error ->
					fields.add(Map.of("field", name == null ? "" : name, "reason", reasonOf(error.getDefaultMessage()))));
		});

		return validationFailure(fields, ex);
	}

	/**
	 * 본문이 DTO 로 풀리지 못한 실패 → 400 {@code VALIDATION_ERROR}. 어긋난 <b>필드 경로만</b> 담는다 (#466).
	 *
	 * <p>{@code @Valid} 이전 단계라 {@link MethodArgumentNotValidException} 이 잡지 못하던 자리다. 예전에는
	 * {@link #handleExceptionInternal} 로 떨어져 {@code details} 가 빈 객체로 나갔고, 그래서 클라이언트는
	 * 무엇을 고쳐야 하는지 알 수 없었다.
	 *
	 * <p><b>예외 메시지는 싣지 않는다.</b> 거기에는 거절된 값 조각과 내부 클래스 경로가 함께 들어 있어
	 * 그대로 내보내면 S-3 과 S-6 을 동시에 어긴다. 실을 수 있는 것은 Jackson 이 기록한 참조 경로의
	 * <b>필드 이름</b>까지이며, 그마저 {@link #SAFE_PROPERTY_NAME} 을 통과한 것만 남긴다.
	 *
	 * <p>경로를 알 수 없는 실패(본문이 애초에 JSON 이 아닌 경우 등)는 {@code details} 를 <b>빈 객체로</b>
	 * 둔다 — 자리를 지어내지 않는다(§9.3).
	 */
	@Override
	protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {

		return validationFailure(malformedBodyFields(ex), ex);
	}

	/**
	 * {@link ResponseEntityExceptionHandler} 가 처리하는 나머지 MVC 예외의 본문을 §9.1 형태로 갈아끼운다.
	 *
	 * <p>기본 구현은 RFC 9457 {@code ProblemDetail} 을 내보내며 거기엔 예외 메시지와 요청 경로가 들어간다.
	 * 응답 형태를 하나로 유지하는 동시에 S-6 을 지키기 위해 통째로 교체한다.
	 */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
			HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {

		ErrorCode errorCode = fromStatus(statusCode);
		logByStatus(errorCode, statusCode, ex);
		return super.handleExceptionInternal(ex, ErrorResponse.of(errorCode), headers, statusCode, request);
	}

	private ResponseEntity<Object> validationFailure(List<Map<String, String>> fields, Exception ex) {
		HttpStatus status = ErrorCode.VALIDATION_ERROR.status();
		log.warn("api.error code={} status={} fieldCount={}", ErrorCode.VALIDATION_ERROR.code(), status.value(),
				fields.size());
		Map<String, Object> details = fields.isEmpty() ? Map.of() : Map.of("fields", List.copyOf(fields));
		return ResponseEntity.status(status).body(ErrorResponse.of(ErrorCode.VALIDATION_ERROR, details));
	}

	/**
	 * MVC 가 정한 HTTP 상태를 §11 카탈로그의 코드로 사상한다.
	 *
	 * <p>카탈로그에 대응 코드가 없는 4xx(405·415·406 등)는 클라이언트 요청 형식 오류이므로
	 * {@code VALIDATION_ERROR} 로 묶는다. 상태 코드 자체는 바꾸지 않는다.
	 */
	private static ErrorCode fromStatus(HttpStatusCode statusCode) {
		if (statusCode.isSameCodeAs(HttpStatus.NOT_FOUND)) {
			return ErrorCode.NOT_FOUND;
		}
		if (statusCode.isSameCodeAs(HttpStatus.UNAUTHORIZED)) {
			return ErrorCode.UNAUTHENTICATED;
		}
		if (statusCode.isSameCodeAs(HttpStatus.FORBIDDEN)) {
			return ErrorCode.FORBIDDEN;
		}
		if (statusCode.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)) {
			return ErrorCode.RATE_LIMITED;
		}
		return statusCode.is4xxClientError() ? ErrorCode.VALIDATION_ERROR : ErrorCode.INTERNAL_ERROR;
	}

	/**
	 * §9.4 — 구조화 로그. 문장형 로그를 만들지 않는다.
	 *
	 * <p>4xx 는 사용자·클라이언트 사정이므로 {@code WARN} 이며 스택트레이스를 남기지 않는다.
	 * 5xx 는 서버 결함이므로 {@code ERROR} + 스택트레이스다.
	 */
	private static void logByStatus(ErrorCode errorCode, HttpStatusCode statusCode, Exception ex) {
		if (statusCode.is5xxServerError()) {
			log.error("api.error code={} status={}", errorCode.code(), statusCode.value(), ex);
		}
		else {
			log.warn("api.error code={} status={} type={}", errorCode.code(), statusCode.value(),
					ex.getClass().getSimpleName());
		}
	}

	/**
	 * 역직렬화 실패에서 응답에 실을 필드 경로를 뽑는다. 뽑을 것이 없으면 빈 목록이다.
	 *
	 * <p>경로를 아는 것은 Jackson 이 본문을 <b>객체로 읽다가</b> 실패한 경우뿐이다. 본문이 JSON 이
	 * 아니어서 파싱 자체가 실패하면 참조 경로가 비어 있고, 그때는 아무것도 담지 않는다.
	 */
	private static List<Map<String, String>> malformedBodyFields(HttpMessageNotReadableException ex) {
		if (!(ex.getCause() instanceof JacksonException jacksonException)) {
			return List.of();
		}
		String path = safePathOf(jacksonException);
		return path.isEmpty() ? List.of() : List.of(Map.of("field", path, "reason", MALFORMED_FIELD_REASON));
	}

	/**
	 * Jackson 의 참조 경로를 {@code scenes[0].title} 모양의 문자열로 만든다.
	 *
	 * <p><b>이름과 인덱스만 읽는다.</b> {@code Reference} 는 실패한 값 자체도 들고 있으므로
	 * {@code toString} 이나 {@code getPathReference} 로 만들지 않는다. 이름 하나라도 모양이 어긋나면
	 * 부분적으로 살리지 않고 <b>경로 전체를 버린다</b> — 반쯤 걸러진 경로가 가장 위험하다.
	 */
	private static String safePathOf(JacksonException ex) {
		List<JacksonException.Reference> references = ex.getPath();
		if (references.isEmpty() || references.size() > MAX_PATH_DEPTH) {
			return "";
		}

		StringBuilder path = new StringBuilder();
		for (JacksonException.Reference reference : references) {
			String propertyName = reference.getPropertyName();
			if (propertyName == null) {
				int index = reference.getIndex();
				if (index < 0 || path.isEmpty()) {
					return "";
				}
				path.append('[').append(index).append(']');
				continue;
			}
			if (!SAFE_PROPERTY_NAME.matcher(propertyName).matches()) {
				return "";
			}
			if (!path.isEmpty()) {
				path.append('.');
			}
			path.append(propertyName);
		}
		return path.toString();
	}

	/** 검증 메시지가 비어 있을 때의 폴백. 응답에 {@code null} 을 흘리지 않는다(§9.3). */
	private static String reasonOf(@Nullable String defaultMessage) {
		return defaultMessage == null ? "invalid" : defaultMessage;
	}
}

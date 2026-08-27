package com.neowadaeum.common.web;

import com.neowadaeum.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 보안 필터가 거절한 요청도 <b>§9.1 형태 하나로</b> 내보낸다 (B-12).
 *
 * <p>{@code GlobalExceptionHandler} 가 자기 javadoc 에 이 자리를 예약해 뒀다 — 인증·인가 예외는
 * 컨트롤러에 닿기 전에 필터에서 끝나므로 {@code @RestControllerAdvice} 가 볼 수 없고, 그대로 두면
 * Spring 의 기본 응답이 나간다. <b>에러 형태가 둘이 되는 지점</b>이다.
 *
 * <p>401 과 403 은 코드만 다르고 나머지가 같아 한 클래스가 두 인터페이스를 구현한다.
 * <b>본문에 무엇이 왜 틀렸는지 적지 않는다</b> (S-6) — 없는 것과 위조된 것과 만료된 것이 같은 응답이다.
 */
@Component
public class ErrorResponseSecurityHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {
		write(response, ErrorCode.UNAUTHENTICATED);
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException {
		write(response, ErrorCode.FORBIDDEN);
	}

	/** 401 은 인증 부재, 403 은 권한 부족. 형태는 같다. */
	private static void write(HttpServletResponse response, ErrorCode errorCode) throws IOException {
		response.setStatus(errorCode.status().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write(JSON.writeValueAsString(ErrorResponse.of(errorCode)));
	}
}

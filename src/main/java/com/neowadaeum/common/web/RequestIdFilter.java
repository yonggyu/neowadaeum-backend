package com.neowadaeum.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청마다 추적 ID 를 발급해 MDC 에 심는다. 구조화 로그(§9.4)에서 한 요청의 로그 전 구간이 하나로 묶인다.
 *
 * <p>발급된 값은 {@code X-Request-Id} 응답 헤더로도 돌려준다. 사용자가 오류를 제보하면 이 값 하나로
 * 서버 로그를 찾을 수 있다 — 응답에 내부 정보를 담지 않고도(S-6) 지원이 가능해지는 지점이다.
 *
 * <p><b>클라이언트가 보낸 헤더는 형식 검증을 통과할 때만 사용한다.</b> 값이 그대로 로그 필드가 되므로,
 * 임의 문자열을 허용하면 개행을 섞은 로그 위조나 과대한 길이가 그대로 들어온다.
 *
 * <p>여기서 쓰는 {@link UUID#randomUUID()} 는 I-15 의 난수 금지 대상이 아니다. §6.1 이 요청 ID·UUID 생성을
 * 명시적으로 허용한다 — 게임 판정에 쓰이지 않기 때문이다.
 */
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String HEADER_NAME = "X-Request-Id";

	/** 구조화 로그에 나타나는 필드명. ECS 포맷은 MDC 를 최상위 필드로 승격한다. */
	public static final String MDC_KEY = "requestId";

	private static final Pattern ACCEPTED = Pattern.compile("[A-Za-z0-9._-]{8,64}");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {

		String requestId = resolve(request.getHeader(HEADER_NAME));
		MDC.put(MDC_KEY, requestId);
		response.setHeader(HEADER_NAME, requestId);
		try {
			filterChain.doFilter(request, response);
		}
		finally {
			MDC.remove(MDC_KEY);
		}
	}

	/**
	 * 비동기 디스패치에서도 MDC 를 다시 심는다.
	 *
	 * <p>기본값은 비동기 재진입 시 필터를 건너뛰는 것이라 다른 스레드에서 이어지는 로그가 추적 ID 를 잃는다.
	 * 턴 파이프라인은 25초까지 대기하므로(§4.3) 이 경로가 실제로 쓰인다.
	 */
	@Override
	protected boolean shouldNotFilterAsyncDispatch() {
		return false;
	}

	private static String resolve(@Nullable String candidate) {
		return candidate != null && ACCEPTED.matcher(candidate).matches()
				? candidate
				: UUID.randomUUID().toString();
	}
}

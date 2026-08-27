package com.neowadaeum.identity.auth;

import com.neowadaeum.common.error.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code Authorization: Bearer} 를 요청 주체로 바꾼다 (B-12, §13-22).
 *
 * <p><b>검증은 {@link AuthTokenService} 한 곳에서만 한다.</b> 필터가 자기 디코더를 따로 가지면
 * {@code token_use} 확인 같은 규칙이 한쪽에만 남는 날이 온다.
 *
 * <p><b>실패해도 여기서 응답을 쓰지 않는다.</b> 토큰이 없는 것과 위조·만료된 것을 똑같이
 * <b>인증되지 않음</b>으로 두고, 거절은 {@code AuthenticationEntryPoint} 가 한 형태로 낸다 (S-6).
 * 주체는 {@code playerRef} 다 (I-3) — {@code user.id} 도 이메일도 올리지 않는다.
 */
@Component
public class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

	private static final String PREFIX = "Bearer ";

	private final AuthTokenService tokens;

	public BearerTokenAuthenticationFilter(AuthTokenService tokens) {
		this.tokens = tokens;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header != null && header.startsWith(PREFIX)) {
			authenticate(header.substring(PREFIX.length()));
		}
		chain.doFilter(request, response);
	}

	/** 실패하면 인증되지 않은 채로 둔다. 토큰 원문도 사유도 로그에 남기지 않는다 (S-3). */
	private void authenticate(String token) {
		try {
			SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken
					.authenticated(this.tokens.authenticate(token), null, List.of()));
		}
		catch (ApiException ex) {
			SecurityContextHolder.clearContext();
		}
	}
}

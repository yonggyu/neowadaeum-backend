package com.neowadaeum.identity.auth;

import com.neowadaeum.common.web.ErrorResponseSecurityHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 플레이 API 의 보안 체인 (B-12, #34).
 *
 * <p><b>이 클래스가 인증 우회를 대체한다.</b> 지금까지 {@code /api/v1/**} 는 {@code dev} 프로파일에서
 * {@code permitAll} 이었고 "누구인가"는 고정 상수였다 (ADR-0004). 이제 <b>토큰이 없으면 401 이다.</b>
 * <b>프로파일 조건이 없다</b> — 조건이 필요했던 것은 우회 쪽이고, {@code dev} 에서도 토큰이 필요하다.
 * 인증 없이 여는 것은 로그인 경로 둘뿐이며 계약의 {@code security: []} 가 그 둘에만 붙어 있다.
 *
 * <p>{@code config} 가 아니라 {@code identity} 에 있다 — 인증 필터는 identity 의 내부 타입이고,
 * {@code config} 에 두면 모듈 경계 검증이 잡는다 (§5.4).
 *
 * <p><b>CSRF 는 끄지 않고 면제 대상을 명시한다.</b> 이 체인은 세션도 쿠키도 쓰지 않으므로
 * ({@code STATELESS}) 자격 증명이 {@code Authorization} 헤더로만 온다 — 브라우저가 자동으로 실어
 * 보내는 것이 없어 위조할 요청이 성립하지 않는다. 그래도 통째로 끄지 않는 이유는 <b>나중에 쿠키
 * 기반 경로가 하나라도 생기면 그 순간 무방비가 되기</b> 때문이다. 이전 체인이 CSRF 를 켠 이유는
 * 그때 <b>인증이 우회된 상태</b>였기 때문이며, 그 조건이 사라졌다.
 */
@Configuration(proxyBeanMethods = false)
public class ApiSecurityConfiguration {

	/** 인증 없이 여는 경로 (§13.1). */
	private static final String[] PUBLIC_PATHS = { "/api/v1/auth/oauth/*", "/api/v1/auth/refresh" };

	/** {@code Bearer} 를 실은 요청 — 브라우저가 자동으로 붙이지 않으므로 쿠키 자격 증명이 아니다. */
	private static final RequestMatcher BEARER_AUTHENTICATED = request -> {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		return header != null && header.startsWith("Bearer ");
	};

	@Bean
	@Order(0)
	public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http,
			BearerTokenAuthenticationFilter bearerTokenFilter,
			ErrorResponseSecurityHandler errorHandler) throws Exception {
		return http
				.securityMatcher("/api/v1/**")
				.csrf(csrf -> csrf.ignoringRequestMatchers(BEARER_AUTHENTICATED)
						.ignoringRequestMatchers(PUBLIC_PATHS))
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(requests -> requests
						.requestMatchers(PUBLIC_PATHS).permitAll().anyRequest().authenticated())
				// 401·403 도 §9.1 형태 하나로 나간다. 없으면 Spring 기본 응답이 섞인다.
				.exceptionHandling(handling -> handling
						.authenticationEntryPoint(errorHandler).accessDeniedHandler(errorHandler))
				.addFilterBefore(bearerTokenFilter, UsernamePasswordAuthenticationFilter.class)
				.build();
	}
}

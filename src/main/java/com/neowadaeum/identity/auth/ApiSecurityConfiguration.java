package com.neowadaeum.identity.auth;

import com.neowadaeum.common.web.ErrorResponseSecurityHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
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
 * <p><b>CSRF 는 이 체인의 경로에서만 면제한다.</b> 여기는 세션도 쿠키도 쓰지 않으므로
 * ({@code STATELESS}) 자격 증명이 {@code Authorization} 헤더로만 온다 — 브라우저가 자동으로 실어
 * 보내는 것이 없어 <b>위조할 요청이 성립하지 않는다.</b> 이전 체인이 CSRF 를 켠 이유는 그때
 * <b>인증이 우회된 상태</b>였기 때문이며(남의 사이트가 고정 {@code player_ref} 로 세션을 만들 수
 * 있었다) 그 조건이 사라졌다.
 *
 * <p><b>{@code disable()} 이 아니라 경로 면제인 것은 의도다.</b> 필터는 남아 있고 다른 체인의
 * 경로는 계속 보호된다. <b>쿠키로 인증하는 경로를 만든다면 {@code /api/v1/**} 밖에 두거나 이
 * 면제를 좁혀야 한다</b> — 그러지 않으면 그 경로가 조용히 무방비가 된다.
 *
 * <p>면제 없이 켜 두면 토큰 없는 요청이 <b>401 이 아니라 403</b> 이 된다 — CSRF 필터가 인가
 * 판정보다 먼저 돌기 때문이다. 계약(§13.1)이 약속한 {@code UNAUTHENTICATED} 가 나가지 않는다.
 */
@Configuration(proxyBeanMethods = false)
public class ApiSecurityConfiguration {

	/**
	 * 인증 없이 여는 경로.
	 *
	 * <p>로그인 둘(§13.1)과 랜딩(§13.10)이다. 계약이 {@code security: []} 로 표시한 것과 같은
	 * 목록이며, <b>여기 없는 경로는 토큰을 요구한다.</b>
	 */
	private static final String[] PUBLIC_PATHS = { "/api/v1/auth/oauth/*", "/api/v1/auth/refresh",
			"/api/v1/landing" };

	/** 이 체인이 맡는 범위. {@code securityMatcher} 와 CSRF 면제가 같은 값을 봐야 한다. */
	private static final String API_PATHS = "/api/v1/**";

	@Bean
	@Order(0)
	public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http,
			BearerTokenAuthenticationFilter bearerTokenFilter,
			ErrorResponseSecurityHandler errorHandler) throws Exception {
		return http
				.securityMatcher(API_PATHS)
				// #248 — CORS 는 인가보다 먼저 판정돼야 한다. preflight(OPTIONS)에는
				// Authorization 헤더가 실리지 않으므로, 인가가 먼저 돌면 401 이 나가고 브라우저는
				// 그것을 CORS 오류로 보고한다 — 원인이 인증이라는 사실이 드러나지 않는다.
				// 정책 자체는 common 이 소유한다 (CorsConfigurationSource 빈).
				.cors(Customizer.withDefaults())
				.csrf(csrf -> csrf.ignoringRequestMatchers(API_PATHS))
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

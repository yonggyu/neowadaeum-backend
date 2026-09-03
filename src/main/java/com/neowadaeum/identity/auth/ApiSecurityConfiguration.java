package com.neowadaeum.identity.auth;

import com.neowadaeum.common.web.ErrorResponseSecurityHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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
 * 인증 없이 여는 것은 로그인 경로 둘과 인증 전 조회들이며 계약의 {@code security: []} 가 같은
 * 목록을 표시한다 (§13-54, 이슈 #306 이 탐색 셋을 더했다).
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
	 * <p>로그인 둘(§13.1)과 랜딩(§13.10), 그리고 약관 메타(이슈 #261)다. 계약이
	 * {@code security: []} 로 표시한 것과 같은 목록이며, <b>여기 없는 경로는 토큰을 요구한다.</b>
	 *
	 * <p>{@code /api/v1/consents} 가 여기 있는 이유는 <b>가입 전에 불리기 때문</b>이다 — 토큰을
	 * 요구하면 아직 회원이 아닌 사람이 약관 판본을 읽을 방법이 없다. 그 대신 <b>그 응답에 회원에
	 * 관한 값이 하나도 없어야 한다</b> (S-9).
	 */
	private static final String[] PUBLIC_PATHS = { "/api/v1/auth/oauth/*", "/api/v1/auth/refresh",
			"/api/v1/landing", "/api/v1/consents" };

	/**
	 * 인증 없이 여는 <b>탐색</b> — {@code GET} 만이다 (§13-54, 이슈 #306).
	 *
	 * <p>랜딩이 추천 작품을 인증 없이 주면서 목록만 토큰을 요구했다. 그래서 <b>서비스를 처음 보는
	 * 사람이 무엇이 있는지 둘러볼 수 없었다</b> — 이슈 #306 이 물은 것이 그것이고, 답은 목록까지
	 * 여는 것이다. 대신 <b>로그인해야 의미가 있는 필드는 익명 응답에서 빈다</b>(§13-54).
	 *
	 * <p><b>메서드를 함께 못박는 것이 요점이다.</b> {@code /api/v1/stories/*} 는 경로만 보면
	 * {@code PATCH .../visibility} 나 앞으로 생길 {@code DELETE .../{storyId}} 와 이웃한다 —
	 * 한 세그먼트짜리 {@code *} 는 {@code /{storyId}/sessions} 를 매칭하지 않지만, <b>같은 경로에
	 * 다른 메서드가 붙는 날</b>은 온다. {@code GET} 으로 좁혀 두면 그 날 조용히 열리지 않는다.
	 *
	 * <p><b>I-8 은 이 목록이 지키는 것이 아니다.</b> 검수·공개 조건은 {@code StoryCatalogFacade}
	 * 의 SQL 한 곳에 있고, 인증은 그 보장을 대신한 적이 없다 — 토큰이 있는 누구에게나 같은 조건이
	 * 걸려 있었다. 여기서 늘어난 것은 <b>부를 수 있는 사람</b>이지 <b>보이는 작품</b>이 아니다.
	 */
	private static final String[] PUBLIC_GET_PATHS = { "/api/v1/library", "/api/v1/library/sections/*",
			"/api/v1/stories/*" };

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
						.requestMatchers(PUBLIC_PATHS).permitAll()
						.requestMatchers(HttpMethod.GET, PUBLIC_GET_PATHS).permitAll()
						.anyRequest().authenticated())
				// 401·403 도 §9.1 형태 하나로 나간다. 없으면 Spring 기본 응답이 섞인다.
				.exceptionHandling(handling -> handling
						.authenticationEntryPoint(errorHandler).accessDeniedHandler(errorHandler))
				.addFilterBefore(bearerTokenFilter, UsernamePasswordAuthenticationFilter.class)
				.build();
	}
}

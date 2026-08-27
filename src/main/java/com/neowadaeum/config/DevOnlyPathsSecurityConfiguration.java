package com.neowadaeum.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * {@code dev} 에서만 존재하는 경로들의 보안 구성 — 콘솔(B-47)과 계약 문서(B-06).
 *
 * <p><b>플레이 API 는 더 이상 여기에 없다.</b> 이 클래스의 이전 이름은
 * {@code DevPlayApiSecurityConfiguration} 이었고 {@code /api/v1/**} 를 {@code permitAll} 했다 —
 * 인증 우회의 나머지 절반이었다 (ADR-0004). B-12 가 {@code ApiSecurityConfiguration} 으로 그 자리를
 * 대체했으므로 <b>남는 것은 dev 전용 읽기 경로뿐</b>이다.
 *
 * <p><b>CSRF 구성도 함께 사라졌다.</b> 여기 남은 경로는 전부 조회이고, 상태를 바꾸는 요청은
 * 토큰 인증 체인으로 간다 — 위조할 쿠키 자격 증명이 없다.
 *
 * <p><b>{@code @Profile("dev & !prod")} 다.</b> {@code "!prod"} 는 프로파일 미지정 배포에서 참이 되고,
 * {@code "dev"} 만 쓰면 둘이 함께 켜진 조합에서 열린다 (#47, #34 에서 확인한 함정).
 * <b>운영에는 이 빈도 매핑도 없다</b> — 두 컨트롤러가 같은 프로파일 조건이다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("dev & !prod")
public class DevOnlyPathsSecurityConfiguration {

	/** dev 콘솔 (S-10, B-47). */
	static final String DEV_CONSOLE_PATH = "/dev/console";

	/**
	 * 계약 문서 경로 (B-06). {@code /v3/api-docs/**} 가 있는 것은 swagger-ui 가 부팅할 때
	 * {@code swagger-config} 를 읽기 때문이며, <b>UI 가 보여 주는 문서는 여전히 계약 파일이다</b>.
	 */
	@Bean
	@Order(10)
	public SecurityFilterChain devOnlyPathsSecurityFilterChain(HttpSecurity http) throws Exception {
		return http
				.securityMatcher(DEV_CONSOLE_PATH, "/openapi.yaml", "/swagger-ui.html", "/swagger-ui/**",
						"/v3/api-docs/**")
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
				.build();
	}
}

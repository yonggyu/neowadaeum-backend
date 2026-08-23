package com.neowadaeum.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * {@code dev} 전용 플레이 API 보안 구성 — <b>인증 우회의 나머지 절반이다</b> (ADR-0004 대체 수단 1).
 *
 * <p>{@code DevFixedPlayerRefResolver} 가 "누구인가"를 고정한다면, 이것은 "들어올 수 있는가"를
 * 연다. 둘이 함께 있어야 슬라이스가 플레이 가능해지고, <b>둘 다 없어야 운영이 닫힌다.</b>
 *
 * <p><b>{@code @Profile("dev & !prod")} — 우회 리졸버와 같은 표현식이다.</b> 한쪽만 막으면 다른
 * 쪽이 남는다. {@code "!prod"} 는 프로파일 미지정 배포에서 참이 되고, {@code "dev"} 만 쓰면 둘이
 * 함께 켜진 조합에서 열린다 (#47 에서 확인한 함정).
 *
 * <p><b>운영에는 이 빈이 없다.</b> 그러면 Spring Boot 의 기본 보안 체인이 적용되어 <b>닫힌 상태</b>로
 * 남는다 — 열린 채로 남는 것이 아니다. 실제 인증 구성은 <b>B-12</b> 이며, 그 착수 시점에 이 클래스와
 * 우회 리졸버를 <b>함께 제거한다</b> (#34).
 *
 * <p>CSRF 를 끄는 이유는 이 경로가 쿠키 세션을 쓰지 않는 API 이고 {@code dev} 에서만 존재하기
 * 때문이다. B-12 가 토큰 인증을 붙이면 그때의 정책으로 다시 정한다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("dev & !prod")
public class DevPlayApiSecurityConfiguration {

	@Bean
	public SecurityFilterChain devPlayApiSecurityFilterChain(HttpSecurity http) throws Exception {
		return http
				.securityMatcher("/api/v1/**")
				.csrf(csrf -> csrf.disable())
				.authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
				.build();
	}
}

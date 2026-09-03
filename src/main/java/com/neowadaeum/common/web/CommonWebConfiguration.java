package com.neowadaeum.common.web;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * common 모듈이 소유하는 웹 인프라 등록 지점.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CorsProperties.class)
public class CommonWebConfiguration {

	/** 계약에 있는 메서드뿐이다 (§13). 없는 메서드를 열어 둘 이유가 없다. */
	private static final List<String> ALLOWED_METHODS =
			List.of("GET", "POST", "PATCH", "DELETE");

	/**
	 * 프론트가 CSRF 토큰을 돌려보내는 자리 (ADR-0008, #278).
	 *
	 * <p>Spring Security 의 기본 이름이며 {@code CookieCsrfTokenRepository} 가 굽는 쿠키
	 * ({@code XSRF-TOKEN})와 짝이다. 허용 목록에 없으면 <b>preflight 에서 막히고</b>, 그때
	 * 브라우저가 보고하는 것은 CSRF 가 아니라 CORS 오류다.
	 */
	private static final String CSRF_HEADER_NAME = "X-XSRF-TOKEN";

	/**
	 * 서버가 <b>실제로 읽는</b> 요청 헤더뿐이다.
	 *
	 * <p>{@code Idempotency-Key} 는 중복 과금을 막는 값이고 (R6.2), {@code X-Request-Id} 는
	 * 클라이언트가 추적 ID 를 이어 붙이는 자리다 ({@link RequestIdFilter}). 목록을 넓히면
	 * <b>서버가 무엇을 보는지</b>가 이 파일에서 사라진다.
	 */
	private static final List<String> ALLOWED_HEADERS = List.of("Authorization", "Content-Type",
			"Idempotency-Key", RequestIdFilter.HEADER_NAME, CSRF_HEADER_NAME);

	/**
	 * 브라우저 스크립트가 <b>읽을 수 있는</b> 응답 헤더.
	 *
	 * <p>{@code X-Request-Id} 하나다. 노출하지 않으면 프론트가 그 값을 화면에 띄울 수 없고,
	 * 그러면 사용자가 오류를 제보할 때 <b>서버 로그와 이어 붙일 값이 사라진다</b> — 그것이
	 * {@link RequestIdFilter} 가 이 헤더를 돌려주는 이유다.
	 */
	private static final List<String> EXPOSED_HEADERS = List.of(RequestIdFilter.HEADER_NAME);

	/**
	 * preflight 캐시 수명.
	 *
	 * <p>턴 요청마다 preflight 가 한 번씩 더 붙으면 <b>사용자가 기다리는 시간이 왕복 하나만큼
	 * 는다.</b> 허용 목록은 배포 때만 바뀌므로 오래 캐시해도 위험이 없다.
	 */
	private static final Duration PREFLIGHT_MAX_AGE = Duration.ofHours(1);

	/**
	 * {@link RequestIdFilter} 를 필터 체인 최선두에 둔다.
	 *
	 * <p>Security 필터보다 앞서야 인증 실패 응답까지 같은 추적 ID 를 갖는다.
	 */
	@Bean
	public FilterRegistrationBean<RequestIdFilter> requestIdFilterRegistration() {
		FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
		return registration;
	}

	/**
	 * {@code Idempotency-Key} 저장소 (R6.2).
	 *
	 * <p>Redis 를 쓰는 이유는 <b>프로세스 간</b> 공유다. 인스턴스가 둘이면 인메모리 맵은 중복 과금을
	 * 아무것도 막지 못한다.
	 */
	@Bean
	public IdempotencyStore idempotencyStore(StringRedisTemplate redis) {
		return new IdempotencyStore(redis);
	}

	/**
	 * CORS 정책 (#248).
	 *
	 * <p><b>Security 체인이 이 빈을 집어 간다</b> ({@code ApiSecurityConfiguration}). MVC 쪽에만
	 * 두면 preflight({@code OPTIONS})가 <b>인가 판정에서 먼저 막혀</b> 컨트롤러에 닿지 못한다 —
	 * 그리고 브라우저는 그것을 CORS 오류로 보고하므로 원인이 인증이라는 사실이 드러나지 않는다.
	 *
	 * <p><b>범위는 {@code /api/v1/**} 다.</b> dev 콘솔(B-47)과 계약 서빙은 같은 오리진에서
	 * 열리므로 CORS 가 필요 없고, 열어 두면 <b>dev 전용 경로가 교차 오리진에서 접근 가능</b>해진다.
	 */
	@Bean
	public CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
		CorsConfiguration cors = new CorsConfiguration();
		cors.setAllowedOrigins(properties.allowedOrigins());
		cors.setAllowedMethods(ALLOWED_METHODS);
		cors.setAllowedHeaders(ALLOWED_HEADERS);
		cors.setExposedHeaders(EXPOSED_HEADERS);
		// ADR-0008(#278) — 리프레시 토큰이 쿠키로 옮겨오면서 켠다. 브라우저는 자격 증명을
		// 실은 교차 오리진 요청의 응답을 이것 없이는 버리므로, 끄면 Set-Cookie 도 무시되고
		// 로그인 유지가 성립하지 않는다.
		//
		// 켤 수 있는 근거는 허용 목록이 **정확 일치이고 와일드카드를 거부한다**는 것 하나다
		// (CorsProperties). 그 검사가 이 줄과 함께 있어야 의미를 갖는다.
		cors.setAllowCredentials(true);
		cors.setMaxAge(PREFLIGHT_MAX_AGE);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/v1/**", cors);
		return source;
	}
}

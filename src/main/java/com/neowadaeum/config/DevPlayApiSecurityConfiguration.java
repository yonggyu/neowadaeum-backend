package com.neowadaeum.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Supplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

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
 * <p><b>CSRF 를 끄지 않는다.</b> 초안은 {@code csrf().disable()} 이었고 CodeQL 이 high 로 잡았다 —
 * 타당한 지적이다. 인증이 우회된 상태에서도 남의 사이트가 이 서버로 요청을 보내 <b>고정
 * {@code player_ref} 의 세션을 만들 수</b> 있다. 우회는 인증을 없앤 것이지 요청 위조를 허용한
 * 것이 아니다.
 *
 * <p>대신 쿠키 기반 토큰 저장소를 쓴다. 이 경로는 서버 세션을 만들지 않으므로 기본
 * {@code HttpSession} 저장소로는 토큰을 둘 곳이 없다. {@code withHttpOnlyFalse()} 는 dev 콘솔(S-10)
 * 같은 동일 출처 스크립트가 토큰을 읽어 헤더로 되돌려 줄 수 있게 한다.
 *
 * <p><b>토큰 검증은 {@link SpaCsrfTokenRequestHandler} 다</b> (S-10). 기본
 * {@link XorCsrfTokenRequestAttributeHandler} 는 헤더 값이 <b>XOR 마스킹된 토큰</b>이라고 가정하는데,
 * 쿠키에는 원본 토큰이 담기므로 브라우저가 쿠키 값을 그대로 헤더에 되돌리면 <b>유효한 토큰이
 * 403 이 된다.</b> MockMvc 의 {@code with(csrf())} 는 이 경로를 지나지 않아 S-9-2 테스트로는 안
 * 잡혔고, 실브라우저 클라이언트(dev 콘솔)가 처음 생기는 S-10 에서 드러났다. Spring Security 문서의
 * SPA 구성 그대로다 — 렌더링은 XOR, <b>헤더로 온 값만</b> 원본 비교.
 *
 * <p>B-12 가 토큰 인증을 붙이면 그때의 정책으로 다시 정한다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("dev & !prod")
public class DevPlayApiSecurityConfiguration {

	/**
	 * dev 콘솔 경로 (S-10, B-47). API 와 같은 체인에 묶는 이유 — <b>이 체인이 없으면 기본 보안
	 * 체인이 닫는다.</b> 콘솔용 체인을 따로 만들면 프로파일 조건을 지켜야 할 지점이 둘로 늘어난다.
	 */
	static final String DEV_CONSOLE_PATH = "/dev/console";

	/**
	 * 계약 문서 경로가 함께 묶여 있다 (B-06). <b>같은 체인에 두는 이유는 콘솔과 같다</b> —
	 * 빠지면 기본 보안 체인이 로그인 폼으로 닫는다.
	 *
	 * <p>{@code OpenApiContractController} 와 springdoc 은 <b>둘 다 {@code dev & !prod} 에서만
	 * 존재한다.</b> 여기에 경로가 있어도 운영에는 매핑이 없다 — 이 체인 자체가 운영에 없고,
	 * springdoc 은 {@code springdoc.api-docs.enabled} 가 기본 {@code false} 라 빈이 만들어지지 않는다.
	 *
	 * <p>{@code /v3/api-docs/**} 가 있는 것은 <b>swagger-ui 가 부팅할 때
	 * {@code /v3/api-docs/swagger-config} 를 읽기 때문</b>이다. 그 경로가 막히면 UI 가 뜨지 않는다.
	 * <b>UI 가 보여 주는 문서는 여전히 계약 파일이다</b> — {@code springdoc.swagger-ui.url} 이
	 * {@code /openapi.yaml} 을 가리킨다. 생성본은 dev 안에 남을 뿐 계약이 아니다.
	 */
	@Bean
	public SecurityFilterChain devPlayApiSecurityFilterChain(HttpSecurity http) throws Exception {
		return http
				.securityMatcher("/api/v1/**", DEV_CONSOLE_PATH, "/openapi.yaml", "/swagger-ui.html",
						"/swagger-ui/**", "/v3/api-docs/**")
				.csrf(csrf -> csrf
						.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
						.csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
				.build();
	}

	/**
	 * 쿠키 저장소용 검증 핸들러 — Spring Security 레퍼런스의 SPA 패턴이다.
	 *
	 * <p>{@code handle()} 의 {@code csrfToken.get()} 이 지연 로딩을 풀어 <b>첫 GET 에서 쿠키가
	 * 발급되게</b> 한다. 없으면 토큰을 소비하는 핸들러가 나올 때까지 쿠키가 안 내려가서, 콘솔이
	 * 첫 POST 를 보낼 때 보낼 토큰이 없다. 레퍼런스 구성은 이 즉시 로딩을 별도
	 * {@code CsrfCookieFilter} 로 분리하는데, 여기서는 핸들러 한 곳에 합쳤다 — dev 전용 체인에
	 * 프로파일 조건을 지켜야 할 조각을 하나 더 만들지 않기 위해서다.
	 */
	static final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

		private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();

		private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

		@Override
		public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
			this.xor.handle(request, response, csrfToken);
			csrfToken.get();
		}

		@Override
		public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
			String headerValue = request.getHeader(csrfToken.getHeaderName());
			// 헤더 = 쿠키에서 읽은 원본 토큰. 파라미터(_csrf)로 오는 값만 XOR 마스킹이다.
			return (StringUtils.hasText(headerValue) ? this.plain : this.xor).resolveCsrfTokenValue(request, csrfToken);
		}
	}
}

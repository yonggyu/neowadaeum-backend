package com.neowadaeum.identity.api;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.common.support.RateLimitProperties;
import com.neowadaeum.common.support.RateLimiter;
import com.neowadaeum.common.support.Sha256;
import com.neowadaeum.identity.auth.AuthTokens;
import com.neowadaeum.identity.auth.OAuthLoginService;
import com.neowadaeum.identity.auth.RefreshTokenCookie;
import com.neowadaeum.identity.domain.OauthProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Locale;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 API (§13.1).
 *
 * <p><b>Controller 는 요청 검증과 DTO 변환만 한다</b> (web-api 규칙). 토큰 발급도 회원 생성도
 * 여기에 없다.
 *
 * <p><b>이 두 경로는 인증을 요구하지 않는다</b> — 계약의 {@code security: []} 가 그렇게 적었다.
 * 그 예외를 실제로 여는 것은 보안 체인이며 B-12(3/3) 다.
 *
 * <p><b>이 컨트롤러만 {@code Set-Cookie} 를 쓴다</b> (ADR-0008, #278). 리프레시 토큰은 응답
 * 본문에 실리지 않고 {@link RefreshTokenCookie} 가 굽는 쿠키로만 오간다 — 그래서 두 응답이
 * {@code HttpServletResponse} 를 받는다.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

	private final OAuthLoginService login;

	private final RateLimiter rateLimiter;

	private final RateLimitProperties limits;

	private final RefreshTokenCookie refreshCookie;

	public AuthController(OAuthLoginService login, RateLimiter rateLimiter, RateLimitProperties limits,
			RefreshTokenCookie refreshCookie) {
		this.login = login;
		this.rateLimiter = rateLimiter;
		this.limits = limits;
		this.refreshCookie = refreshCookie;
	}

	/**
	 * <b>S-8 — 계정 없이 부를 수 있는 경로는 IP 로 센다.</b>
	 *
	 * <p>이 두 경로는 인증 전이므로 계정 기준 한도를 걸 수 없다. 걸지 않으면 <b>ID 토큰을
	 * 무작위로 던져 보는 요청</b>과 리프레시 토큰 추측이 무제한이 된다.
	 *
	 * <p>IP 는 <b>해시로만</b> 센다 (§12) — 키에 원문을 넣으면 Redis 가 접속자 목록이 된다.
	 */
	private void requireWithinIpLimit(HttpServletRequest request) {
		String ipHash = Sha256.hex(request.getRemoteAddr());
		if (ipHash == null) {
			return;
		}
		if (!this.rateLimiter.tryAcquire("auth-ip", ipHash, this.limits.authPerMinutePerIp(),
				RateLimitProperties.MINUTE)) {
			throw new ApiException(ErrorCode.RATE_LIMITED, java.util.Map.of("retryAfterSeconds",
					this.rateLimiter.retryAfterSeconds(RateLimitProperties.MINUTE)));
		}
	}

	/**
	 * 소셜 로그인·가입 (§13.1, §4.1).
	 *
	 * <p>경로의 {@code provider} 는 계약이 {@code [google]} 로 좁혀 뒀다 (§13-11). 목록에 없는
	 * 값은 {@code 400 VALIDATION_ERROR} 다 — 존재하지 않는 경로가 아니라 <b>보낼 수 없는 값</b>이다.
	 *
	 * <p><b>IP 를 원문으로 넘기지 않는다</b> (§12). 여기서 해시로 바꾸므로 서비스도 저장소도
	 * 원문을 볼 방법이 없다.
	 *
	 * <p><b>프록시 뒤에서는 {@code getRemoteAddr()} 이 프록시를 가리킨다.</b> 전달 헤더를
	 * <b>신뢰된 프록시에서 온 것만</b> 신뢰한다는 원칙이 §13-45 로 정해졌다 — 무조건 믿으면
	 * 헤더 한 줄로 S-8 의 IP 기준 한도를 우회할 수 있다. 신뢰 경계를 실제 값으로 세우는 것은
	 * 배포 환경이 정해지는 시점이다 ({@code docs/deployment.md} §5, 이슈 #224).
	 */
	@PostMapping("/oauth/{provider}")
	public TokenResponse loginWithOAuth(@PathVariable String provider,
			@Valid @RequestBody OAuthLoginRequest request, HttpServletRequest httpRequest,
			HttpServletResponse httpResponse) {
		requireWithinIpLimit(httpRequest);
		return issue(this.login.login(providerOf(provider), request.idToken(), request.toSignupInfo(),
				Sha256.hex(httpRequest.getRemoteAddr())), httpResponse);
	}

	/**
	 * 액세스 토큰 재발급 (§13.1, ADR-0008). 액세스 토큰으로는 통하지 않는다.
	 *
	 * <p><b>요청에 본문이 없다.</b> 자격 증명은 재발급 경로 전용 쿠키 하나이며, 본문으로도 받으면
	 * {@code HttpOnly} 가 주는 보장이 문장으로만 남는다 (#278).
	 *
	 * <p>쿠키가 없으면 {@code 401} 이다 — <b>토큰이 틀린 것과 같은 응답</b>이다 (S-6).
	 */
	@PostMapping("/refresh")
	public TokenResponse refresh(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
		requireWithinIpLimit(httpRequest);
		String refreshToken = this.refreshCookie.readFrom(httpRequest)
				.orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
		return issue(this.login.refresh(refreshToken), httpResponse);
	}

	/**
	 * 발급된 한 벌을 <b>본문과 쿠키로 나눈다</b> (ADR-0008).
	 *
	 * <p>액세스 토큰은 본문으로, 리프레시 토큰은 쿠키로 간다. 로그인과 재발급이 같은 자리를 쓰는
	 * 이유는 <b>두 경로가 갈리면 한쪽만 고치는 일이 생기기 때문</b>이다.
	 */
	private TokenResponse issue(AuthTokens tokens, HttpServletResponse response) {
		this.refreshCookie.writeTo(response, tokens.refreshToken());
		return TokenResponse.of(tokens);
	}

	private static OauthProvider providerOf(String provider) {
		try {
			return OauthProvider.valueOf(provider.toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, ex);
		}
	}
}

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
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 API (§13.1).
 *
 * <p><b>Controller 는 요청 검증과 DTO 변환만 한다</b> (web-api 규칙). 토큰 발급도 회원 생성도
 * 여기에 없다.
 *
 * <p><b>이 세 경로는 인증을 요구하지 않는다</b> — 계약의 {@code security: []} 가 그렇게 적었다.
 * 그 예외를 실제로 여는 것은 보안 체인이며 B-12(3/3) 다. nonce 발급이 셋째이며, 그것이
 * <b>로그인보다도 앞</b>이라 인증을 요구할 자격 증명 자체가 없다 (§13-87).
 *
 * <p><b>이 컨트롤러만 {@code Set-Cookie} 를 쓴다</b> (ADR-0008, #278). 리프레시 토큰은 응답
 * 본문에 실리지 않고 {@link RefreshTokenCookie} 가 굽는 쿠키로만 오간다 — 그래서 세 응답이
 * {@code HttpServletResponse} 를 받는다. <b>셋째가 로그아웃</b>이며, 그것만 굽는 대신 지운다
 * (#473).
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
	 * <p>이 세 경로는 인증 전이므로 계정 기준 한도를 걸 수 없다. 걸지 않으면 <b>ID 토큰을
	 * 무작위로 던져 보는 요청</b>과 리프레시 토큰 추측이 무제한이 된다.
	 *
	 * <p><b>nonce 발급에는 이유가 하나 더 있다</b> (§13-87). 그 경로는 부를 때마다 <b>서버에
	 * 상태를 만든다</b> — 한도가 없으면 누구나 Redis 를 채울 수 있고, 그것은 로그인 실패가
	 * 아니라 서비스 전체의 문제가 된다. <b>셋이 같은 창을 쓴다</b>: 나누면 한쪽이 다른 쪽의
	 * 우회로가 된다.
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
	 * 로그인 nonce 발급 (§13-87, 이슈 #424).
	 *
	 * <p><b>요청에 본문이 없다.</b> 받을 것이 없다 — 만드는 쪽이 서버이기 때문이고, 그것이
	 * 이 결정의 전부다. 클라이언트가 만든 값을 로그인 요청에 함께 받으면 <b>같은 요청 안의
	 * 값을 자기 자신과 비교</b>하는 꼴이라 대조가 성립하지 않는다.
	 *
	 * <p>{@code POST} 인 것은 <b>서버에 상태를 만들기 때문</b>이다. 같은 요청을 두 번 보내면
	 * 값이 둘 생긴다 — 조회가 아니다.
	 */
	@PostMapping("/nonce")
	public AuthNonceResponse issueLoginNonce(HttpServletRequest httpRequest) {
		requireWithinIpLimit(httpRequest);
		return AuthNonceResponse.of(this.login.issueNonce());
	}

	/**
	 * 소셜 로그인·가입 (§13.1, §4.1).
	 *
	 * <p>경로의 {@code provider} 는 계약이 {@code [google]} 로 좁혀 뒀다 (§13-11). 목록에 없는
	 * 값은 {@code 400 VALIDATION_ERROR} 다 — 존재하지 않는 경로가 아니라 <b>보낼 수 없는 값</b>이다.
	 *
	 * <p><b>ID 토큰은 이 서버가 발급한 nonce 를 담고 있어야 한다</b> (§13-87). 담기지 않았거나
	 * 이미 쓰인 값이면 {@code 401 LOGIN_NONCE_INVALID} 이며, 회복은 <b>nonce 부터 다시 받는
	 * 것</b>이다. 그 값은 요청 본문이 아니라 <b>토큰의 클레임</b>으로 온다.
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
	 * 로그아웃 — <b>이 브라우저에서 흔적을 지운다</b> (#473, §13-60 의 <i>범위 밖</i> 을 정정한다).
	 *
	 * <p><b>재발급과 같은 경로에 둔 것이 이 설계의 전부다.</b> 쿠키의 {@code Path} 가
	 * {@link RefreshTokenCookie#PATH} 하나이므로 <b>다른 경로에 두면 쿠키가 실려 오지 않는다.</b>
	 * 같은 자리에 두면 세 가지가 저절로 맞는다 — 쿠키가 오고, {@code PUBLIC_PATHS} 가 이미 이
	 * 경로를 열어 두었고, CSRF 면제에서 빠지는 경로도 그대로다. <b>보안 설정이 한 줄도 바뀌지
	 * 않는다는 것이 이 자리가 맞다는 근거다.</b>
	 *
	 * <p><b>검증하지 않는다.</b> 토큰이 만료됐든 애초에 없든 <b>언제나 {@code 204}</b> 다.
	 *
	 * <ul>
	 * <li>검증하면 <b>만료된 토큰을 든 사람이 나가지 못한다</b> — 로그아웃이 가장 필요한 상태에서
	 * 막히는 셈이다
	 * <li>없는 쿠키에 {@code 401} 을 주면 화면은 <i>나가지 못했다</i> 를 그려야 하는데 실제로는
	 * <b>이미 나가 있다.</b> 상태를 없애는 요청은 없던 것을 없애는 것도 성공이다
	 * </ul>
	 *
	 * <p><b>IP 한도를 걸지 않는다.</b> S-8 이 위 셋에 한도를 건 근거는 <i>추측을 막는 것</i>과
	 * <i>서버에 상태를 만드는 것</i>인데, 이 경로는 <b>아무것도 검증하지 않고 아무 상태도 만들지
	 * 않는다.</b> 반대로 걸면 공용 IP 뒤의 사람이 <b>나가지 못하는</b> 실패가 생긴다.
	 *
	 * <p><b>서버 측 무효화는 없다.</b> 리프레시 토큰이 상태 없는 서명 JWT 라 무를 레코드가 없다 —
	 * 탈취된 토큰까지 무르려면 거부 목록이라는 새 상태가 필요하며 그것은 별개의 결정이다.
	 */
	@DeleteMapping("/refresh")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void logout(HttpServletResponse httpResponse) {
		this.refreshCookie.clearFrom(httpResponse);
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

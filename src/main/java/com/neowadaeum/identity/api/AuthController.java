package com.neowadaeum.identity.api;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.common.support.Sha256;
import com.neowadaeum.identity.auth.AuthTokens;
import com.neowadaeum.identity.auth.OAuthLoginService;
import com.neowadaeum.identity.domain.OauthProvider;
import jakarta.servlet.http.HttpServletRequest;
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
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

	private final OAuthLoginService login;

	public AuthController(OAuthLoginService login) {
		this.login = login;
	}

	/**
	 * 소셜 로그인·가입 (§13.1, §4.1).
	 *
	 * <p>경로의 {@code provider} 는 계약이 {@code [google]} 로 좁혀 뒀다 (§13-11). 목록에 없는
	 * 값은 {@code 400 VALIDATION_ERROR} 다 — 존재하지 않는 경로가 아니라 <b>보낼 수 없는 값</b>이다.
	 *
	 * <p><b>IP 를 원문으로 넘기지 않는다</b> (§12). 여기서 해시로 바꾸므로 서비스도 저장소도
	 * 원문을 볼 방법이 없다. 프록시 뒤에서는 {@code getRemoteAddr()} 이 프록시를 가리키며,
	 * 전달 헤더를 신뢰할지는 배포 구성의 문제다 (B-63).
	 */
	@PostMapping("/oauth/{provider}")
	public TokenResponse loginWithOAuth(@PathVariable String provider,
			@Valid @RequestBody OAuthLoginRequest request, HttpServletRequest httpRequest) {
		return TokenResponse.of(this.login.login(providerOf(provider), request.idToken(),
				request.toSignupInfo(), Sha256.hex(httpRequest.getRemoteAddr())));
	}

	/** 액세스 토큰 재발급 (§13.1). 액세스 토큰으로는 통하지 않는다. */
	@PostMapping("/refresh")
	public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
		return TokenResponse.of(this.login.refresh(request.refreshToken()));
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

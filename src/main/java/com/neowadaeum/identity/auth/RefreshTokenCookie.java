package com.neowadaeum.identity.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * 리프레시 토큰이 브라우저에 머무는 자리 (ADR-0008, #278).
 *
 * <p><b>이 클래스가 ADR-0008 의 전부다.</b> 리프레시 토큰은 더 이상 응답 본문에 실리지 않고
 * 여기서 굽는 쿠키로만 오간다 — {@code HttpOnly} 이므로 <b>XSS 가 읽지 못하고</b>, 브라우저가
 * 자동으로 붙이므로 <b>새로고침을 견딘다.</b> 그 둘을 동시에 만족하는 브라우저 기능은 이것
 * 하나뿐이다.
 *
 * <p>속성 넷 중 셋을 코드가 고정한다. <b>배포마다 달라질 값이 아니라 이 설계가 성립하기 위한
 * 조건</b>이기 때문이다.
 *
 * <ul>
 * <li><b>{@code HttpOnly}</b> — 이 결정의 이유 자체다. 끄면 {@code localStorage} 와 같아진다
 * <li><b>{@code Path=/api/v1/auth/refresh}</b> — 브라우저가 <b>다른 어떤 경로에도 붙이지
 * 않는다.</b> 넓히면 30일짜리 자격 증명이 모든 API 요청·프록시·오류 리포트를 지나가고, CSRF 를
 * 감당해야 하는 경로가 하나에서 전부로 는다
 * <li><b>{@code SameSite=Strict}</b> — 교차 <b>사이트</b> 요청에 아예 붙지 않는다.
 * {@code Lax} 는 이 경로({@code fetch} 의 {@code POST})에서 {@code Strict} 와 구별되지 않고,
 * {@code None} 은 CSRF 토큰을 프론트에 전달할 방법을 없앤다 (ADR-0008)
 * </ul>
 *
 * <p>{@code Secure} 만 {@link RefreshCookieProperties} 가 받는다 — 그것은 설계 조건이 아니라
 * <b>환경이 HTTPS 인가</b>라는 배포 사실이다.
 *
 * <p><b>{@code __Host-} 접두어를 쓰지 않는다.</b> 그 접두어는 {@code Path=/} 를 요구하므로 위의
 * 경로 축소와 정면으로 충돌한다.
 */
@Component
public class RefreshTokenCookie {

	/**
	 * 이 쿠키가 붙는 유일한 경로.
	 *
	 * <p>{@code ApiSecurityConfiguration} 이 CSRF 면제를 좁힐 때 같은 값을 본다 — <b>쿠키가 붙는
	 * 경로와 CSRF 를 요구하는 경로는 같아야 한다.</b> 어긋나면 한쪽은 무방비가 되고 다른 쪽은
	 * 이유 없이 403 이 된다.
	 */
	public static final String PATH = "/api/v1/auth/refresh";

	/** 쿠키 이름. 값이 무엇인지 이름으로 드러나지 않게 한다 — 자격 증명의 종류는 단서다. */
	static final String NAME = "nwd_rt";

	/**
	 * 교차 <b>사이트</b> 요청에는 아예 붙지 않는다 (ADR-0008).
	 *
	 * <p>{@code ApiSecurityConfiguration} 의 CSRF 쿠키가 같은 값을 쓴다 — 둘이 어긋나면 한쪽만
	 * 붙는 요청이 생기고, 그때 나가는 것은 원인을 말해 주지 않는 403 이다.
	 */
	static final String SAME_SITE = "Strict";

	private final JwtProperties jwt;

	private final RefreshCookieProperties properties;

	public RefreshTokenCookie(JwtProperties jwt, RefreshCookieProperties properties) {
		this.jwt = jwt;
		this.properties = properties;
	}

	/**
	 * 발급된 리프레시 토큰을 쿠키로 굽는다. 로그인과 재발급이 함께 쓴다.
	 *
	 * <p>수명은 <b>토큰의 수명과 같다</b> ({@link JwtProperties#refreshTokenTtl()}). 다르게 잡으면
	 * 둘 중 짧은 쪽이 실질 수명이 되고, 그 사실이 어느 설정에도 적혀 있지 않게 된다.
	 */
	public void writeTo(HttpServletResponse response, String refreshToken) {
		response.addHeader(HttpHeaders.SET_COOKIE, build(refreshToken)
				.maxAge(this.jwt.refreshTokenTtl())
				.build()
				.toString());
	}

	/**
	 * 요청이 들고 온 리프레시 토큰. 없으면 비어 있다.
	 *
	 * <p><b>본문은 보지 않는다.</b> 자격 증명을 받는 자리가 둘이면 {@code HttpOnly} 가 주는
	 * 보장이 문장으로만 남는다 (ADR-0008).
	 */
	public Optional<String> readFrom(HttpServletRequest request) {
		if (request.getCookies() == null) {
			return Optional.empty();
		}
		return Arrays.stream(request.getCookies())
				.filter(cookie -> NAME.equals(cookie.getName()))
				.map(Cookie::getValue)
				.filter(value -> value != null && !value.isBlank())
				.findFirst();
	}

	private ResponseCookie.ResponseCookieBuilder build(String value) {
		return ResponseCookie.from(NAME, value)
				.httpOnly(true)
				.secure(this.properties.secure())
				.path(PATH)
				.sameSite(SAME_SITE);
	}
}

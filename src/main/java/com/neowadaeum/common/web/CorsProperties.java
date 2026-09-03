package com.neowadaeum.common.web;

import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 브라우저가 이 API 를 부를 수 있는 오리진 (#248).
 *
 * <p>프론트는 <b>별도 레포</b>이고 (§8.2) 브라우저에서 보면 다른 오리진이다. 허용 목록이 없으면
 * 첫 요청이 preflight 에서 막힌다.
 *
 * <p><b>기본값을 두지 않는다</b> (§7.3 — {@code ${VAR:값}} 금지). 값이 없으면 부팅이 실패한다.
 * 빈 목록을 "아무 오리진도 허용하지 않음"으로 조용히 읽으면, 설정을 빠뜨린 배포가 <b>정상 부팅한
 * 뒤 남의 브라우저에서</b> 실패한다 — 그 실패는 서버 로그에 남지 않는다.
 *
 * <p><b>운영 오리진을 이 레포에 적지 않는다</b> (S-11). 여기 있는 것은 키와 형식뿐이다.
 *
 * <p><b>자격 증명이 붙는 목록이다</b> (ADR-0008, #278). 리프레시 토큰이 재발급 경로 전용 쿠키로
 * 옮겨오면서 {@code Access-Control-Allow-Credentials} 가 켜졌다 — 이제 이 목록은 <i>"응답을 읽을
 * 수 있는 곳"</i> 이 아니라 <b>"자격 증명을 붙여 부를 수 있는 곳"</b> 이다.
 *
 * <p>그래서 아래 <b>와일드카드 거부가 선택에서 필수로 바뀌었다.</b> 자격 증명을 켠 채 와일드카드를
 * 허용하는 것은 브라우저가 거부하는 조합이기도 하지만, 그 전에 이 서비스가 허용해서는 안 되는
 * 조합이다.
 *
 * @param allowedOrigins 스킴과 호스트(포트)까지. 경로·끝 슬래시·와일드카드를 받지 않는다
 */
@ConfigurationProperties("app.cors")
public record CorsProperties(List<String> allowedOrigins) {

	public CorsProperties {
		if (allowedOrigins == null || allowedOrigins.isEmpty()) {
			throw new IllegalStateException(
					"app.cors.allowed-origins is required — a browser-facing API without a declared "
							+ "origin policy is incomplete configuration, not a minimal one (#248)");
		}
		allowedOrigins.forEach(CorsProperties::requireOrigin);
		allowedOrigins = List.copyOf(allowedOrigins);
	}

	/**
	 * <b>와일드카드를 받지 않는다.</b> {@code *} 는 "누구나"이고, 이 API 는 회원의 플레이 기록과
	 * 저작 원고를 다룬다 (I-8). 그리고 자격 증명을 켜는 날 조용히 위험해진다.
	 *
	 * <p><b>오리진에 경로가 없다.</b> 브라우저가 보내는 {@code Origin} 헤더는 스킴·호스트·포트
	 * 뿐이며, 경로를 적어 두면 <b>어떤 요청과도 일치하지 않는다</b> — 그리고 그 사실은 부팅이
	 * 아니라 브라우저에서 드러난다.
	 */
	private static void requireOrigin(String value) {
		if (value == null || value.isBlank() || value.contains("*")) {
			throw new IllegalStateException(
					"app.cors.allowed-origins must list concrete origins — no wildcards (#248)");
		}
		URI uri = URI.create(value);
		boolean shaped = uri.getScheme() != null && uri.getHost() != null
				&& (uri.getPath() == null || uri.getPath().isEmpty())
				&& uri.getQuery() == null && uri.getFragment() == null;
		if (!shaped) {
			throw new IllegalStateException(
					"app.cors.allowed-origins takes scheme://host[:port] with no path (#248)");
		}
	}
}

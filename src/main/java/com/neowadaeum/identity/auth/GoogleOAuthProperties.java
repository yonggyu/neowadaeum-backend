package com.neowadaeum.identity.auth;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Google ID 토큰 검증 설정 (B-12, §13-11).
 *
 * <p>{@code clientId} 는 <b>대상({@code aud}) 검증에 쓴다.</b> 없으면 다른 서비스용으로 발급된
 * 구글 토큰이 이 서버에서도 통한다 — 구글이 서명한 것은 맞지만 <b>우리에게 발급된 것이 아니다.</b>
 *
 * <p>{@code jwkSetUri} 는 코드가 기본값을 갖는다. 배포마다 정할 값이 아니고 시크릿도 아니다 —
 * 테스트가 고정 응답 서버를 가리킬 때만 바꾼다. {@code ${VAR:실제값}} 금지(§7.3)는 <b>설정 파일의
 * 기본값</b>에 대한 규칙이며, 코드 상수는 그 대상이 아니다({@code AnthropicProperties.baseUrl} 과 같다).
 */
@Validated
@ConfigurationProperties("security.oauth2.google")
public record GoogleOAuthProperties(@NotBlank String clientId, String jwkSetUri) {

	private static final String DEFAULT_JWK_SET_URI = "https://www.googleapis.com/oauth2/v3/certs";

	public GoogleOAuthProperties {
		jwkSetUri = (jwkSetUri != null && !jwkSetUri.isBlank()) ? jwkSetUri : DEFAULT_JWK_SET_URI;
	}
}

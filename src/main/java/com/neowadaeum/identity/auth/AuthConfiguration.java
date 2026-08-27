package com.neowadaeum.identity.auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * identity 인증 배선 (B-12).
 *
 * <p>설정 클래스를 모듈 안에 두는 것은 다른 모듈의 관례와 같다({@code ai.gateway} ·
 * {@code ai.provider.*}). {@code config} 는 스토어 배선만 갖는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ JwtProperties.class, GoogleOAuthProperties.class })
public class AuthConfiguration {

}

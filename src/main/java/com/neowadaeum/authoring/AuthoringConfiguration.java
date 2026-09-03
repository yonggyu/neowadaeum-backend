package com.neowadaeum.authoring;

import com.neowadaeum.authoring.image.ImageStorageProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * {@code authoring} 이 혼자 읽는 설정을 활성화한다 (B-60).
 *
 * <p><b>{@code SharedPropertiesConfiguration} 에 두지 않았다.</b> 그쪽은 이름 그대로 <b>둘
 * 이상의 모듈이 읽는</b> 설정을 모으는 자리이고, 여기 값은 작품 만들기 경로만 본다 —
 * {@code ai/gateway} 와 {@code identity/auth} 도 자기 설정을 자기가 연다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ UgcLimitProperties.class, ImageStorageProperties.class })
public class AuthoringConfiguration {
}

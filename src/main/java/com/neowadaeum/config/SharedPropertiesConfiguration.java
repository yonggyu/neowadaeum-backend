package com.neowadaeum.config;

import com.neowadaeum.common.support.RecentTurnsProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * <b>둘 이상의 모듈이 읽는 설정을 여기서 활성화한다</b> (#97, §5.4).
 *
 * <p>{@link RecentTurnsProperties} 는 {@code ai} 의 조립 예산과 {@code play} 의 조회 창을 <b>같은
 * 세 값</b>으로 묶는다 (§13-2). 소유가 {@code common} 이므로 활성화도 특정 모듈의 구성에 매달지
 * 않는다 — {@code ai} 쪽 구성이 조건부가 되는 날 {@code play} 가 함께 무너지면 안 된다.
 *
 * <p>설정 접두어는 {@code ai.prompt.recent-turns} 그대로다. §13-2 가 그 이름으로 부르며, 값이
 * 사는 모듈이 바뀌었다고 배포 설정 키를 바꿀 이유는 없다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RecentTurnsProperties.class)
public class SharedPropertiesConfiguration {
}

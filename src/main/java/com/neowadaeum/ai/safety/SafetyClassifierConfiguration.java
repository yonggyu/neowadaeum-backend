package com.neowadaeum.ai.safety;

import com.neowadaeum.ai.provider.StoryProvider;
import com.neowadaeum.common.spi.SafetyClassifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 분류 SPI 를 채우는 배선 (B-30).
 *
 * <p><b>게이트웨이를 받는다.</b> 어느 어댑터가 불릴지는 설정이 정하고(R3.1) 그 결정을 수행하는 것은
 * {@code AiGateway} 다 — 판정 호출이 그 결정을 우회하면 <b>턴은 A 로 만들고 판정은 B 로</b> 하는
 * 상태가 생긴다.
 */
@Configuration(proxyBeanMethods = false)
public class SafetyClassifierConfiguration {

	@Bean
	public SafetyClassifier providerSafetyClassifier(StoryProvider gateway) {
		return new ProviderSafetyClassifier(gateway);
	}
}

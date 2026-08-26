package com.neowadaeum.ai.prompt;

import com.neowadaeum.common.support.ApproximateTokenCounter;
import com.neowadaeum.common.support.TokenCounter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 프롬프트 조립 배선 (B-20).
 *
 * <p><b>토큰 계산기는 {@code common} 이 소유하고 여기서 고른다</b> (#82, §5.4). 운영에서는 보수적
 * 근사 하나뿐이며, 테스트가 고정 계산기로 갈아끼운다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RecentTurnsProperties.class)
public class PromptConfiguration {

	@Bean
	public TokenCounter tokenCounter() {
		return new ApproximateTokenCounter();
	}

	@Bean
	public PromptAssembler promptAssembler(TokenCounter tokenCounter, RecentTurnsProperties recentTurns) {
		return new PromptAssembler(tokenCounter, recentTurns);
	}
}

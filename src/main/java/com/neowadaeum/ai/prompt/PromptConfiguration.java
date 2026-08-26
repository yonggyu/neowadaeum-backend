package com.neowadaeum.ai.prompt;

import com.neowadaeum.common.support.RecentTurnsProperties;

import com.neowadaeum.common.support.ApproximateTokenCounter;
import com.neowadaeum.common.support.TokenCounter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 프롬프트 조립 배선 (B-20).
 *
 * <p><b>토큰 계산기는 {@code common} 이 소유하고 여기서 고른다</b> (#82, §5.4). 운영에서는 보수적
 * 근사 하나뿐이며, 테스트가 고정 계산기로 갈아끼운다.
 *
 * <p><b>{@code RecentTurnsProperties} 도 {@code common} 이 소유한다</b> (#97). 활성화는
 * {@code config} 가 한다 — {@code play} 도 같은 값을 읽으므로, 이 구성이 조건부가 되는 날
 * {@code play} 가 함께 무너지면 안 된다.
 */
@Configuration(proxyBeanMethods = false)
public class PromptConfiguration {

	@Bean
	public TokenCounter tokenCounter() {
		return new ApproximateTokenCounter();
	}

	@Bean
	public PromptAssembler promptAssembler(TokenCounter tokenCounter, RecentTurnsProperties recentTurns) {
		return new PromptAssembler(tokenCounter, recentTurns);
	}

	/**
	 * 포트 계약 → 프롬프트 매핑 (B-22).
	 *
	 * <p>어댑터가 주입받는다. 어댑터마다 자기 매핑을 갖게 하면 <b>한쪽만 필드를 빠뜨렸을 때 그
	 * 사실이 "프롬프트가 이상하다"로만 나타난다.</b>
	 */
	@Bean
	public TurnPromptFactory turnPromptFactory(PromptAssembler assembler) {
		return new TurnPromptFactory(assembler);
	}
}

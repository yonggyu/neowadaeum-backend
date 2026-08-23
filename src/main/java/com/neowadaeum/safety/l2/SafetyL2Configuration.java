package com.neowadaeum.safety.l2;

import com.neowadaeum.common.spi.BlocklistQuery;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * L2 판정기 배선 (S-8, ADR-0002).
 *
 * <p><b>{@link BlocklistQuery} 를 필수 인자로 받는 것이 fail-fast 의 전부다.</b> 구현 빈이 없으면
 * 스프링이 이 빈을 만들지 못하고 <b>부팅이 멈춘다.</b> {@code @ConditionalOnMissingBean} 으로
 * 기본 구현을 끼워 넣지 않는다 — 그 순간 "블록리스트 없이도 뜨는" 경로가 생기고, 세이프티에서
 * 그것은 검수가 없는 것과 같다.
 */
@Configuration(proxyBeanMethods = false)
public class SafetyL2Configuration {

	@Bean
	public RuleBasedSafetyJudge ruleBasedSafetyJudge(BlocklistQuery blocklistQuery) {
		return new RuleBasedSafetyJudge(blocklistQuery);
	}
}

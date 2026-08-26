package com.neowadaeum.safety.l2;

import com.neowadaeum.common.spi.BlocklistQuery;
import com.neowadaeum.common.spi.SafetyClassifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * L2 판정기 배선 (S-8, ADR-0002).
 *
 * <p><b>두 SPI 를 필수 인자로 받는 것이 fail-fast 의 전부다</b> — 블록리스트 조회(1단)와 의미 기반
 * 분류(2단, B-30). 둘 중 하나라도 구현 빈이 없으면 부팅이 멈춘다.
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

	/**
	 * L2 판정기 (R9.2 의 2단 구성).
	 *
	 * <p><b>{@code @ConditionalOnMissingBean} 으로 1단만 쓰는 폴백을 두지 않는다.</b> 그 순간
	 * "2단 없이도 뜨는" 경로가 생기고, 그것은 설정 실수 하나로 <b>탐지가 절반이 되는데 아무도
	 * 모르는</b> 상태다 (ADR-0002 와 같은 판단).
	 */
	@Bean
	public SafetyL2Judge safetyL2Judge(RuleBasedSafetyJudge ruleBasedSafetyJudge, SafetyClassifier classifier) {
		return new SafetyL2Judge(ruleBasedSafetyJudge, classifier);
	}
}

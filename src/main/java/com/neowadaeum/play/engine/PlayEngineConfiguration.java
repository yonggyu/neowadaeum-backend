package com.neowadaeum.play.engine;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 판정 엔진 배선 (S-9-1).
 *
 * <p>엔진들은 <b>스프링을 모르는 순수 클래스</b>다 — 단위 테스트가 컨테이너 없이 도는 이유이고
 * (ADR-0001), 그 성질을 유지하려고 애노테이션을 클래스가 아니라 여기에 둔다.
 */
@Configuration(proxyBeanMethods = false)
public class PlayEngineConfiguration {

	@Bean
	public ConditionEvaluator conditionEvaluator() {
		return new ConditionEvaluator();
	}

	@Bean
	public GameStateEngine gameStateEngine() {
		return new GameStateEngine();
	}

	@Bean
	public ChapterEngine chapterEngine(ConditionEvaluator conditionEvaluator) {
		return new ChapterEngine(conditionEvaluator);
	}

	@Bean
	public EndingEngine endingEngine(ConditionEvaluator conditionEvaluator) {
		return new EndingEngine(conditionEvaluator);
	}
}

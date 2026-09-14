package com.neowadaeum.ai.schema;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 벤더와 무관한 턴 출력 파서를 한 곳에서 등록한다 (B-21, B-22-1). */
@Configuration(proxyBeanMethods = false)
public class TurnOutputConfiguration {

	@Bean
	public TurnOutputParser turnOutputParser() {
		return new TurnOutputParser();
	}
}

package com.neowadaeum.ai.provider.fixed;

import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * {@code FixedStoryProvider} 등록 경계 (S-3).
 *
 * <p><b>{@code prod} 에서는 이 구성이 통째로 비활성이다</b> (R3.1, I-14, ADR-0004 "대체 수단의 안전 조건" 2).
 * 결정론 Provider 가 운영 등록 경로에 섞이면 실제 사용자에게 고정 응답이 나갈 수 있고, 그 사고는
 * 조용하다 — 에러가 아니라 "이상한 이야기"로 나타난다.
 *
 * <p>{@code @Profile("!prod")} 를 쓴 것은 <b>기본값이 비활성이 아니라 차단이 명시적</b>이어야 하기
 * 때문이다. 새 프로파일이 생겨도 {@code prod} 만 아니면 개발·테스트 편의는 유지되고, {@code prod} 는
 * 프로파일 이름 하나로 확실히 닫힌다.
 *
 * <p>이 보장은 애노테이션이 붙어 있다는 확인이 아니라 <b>동작</b>으로 검증한다 —
 * {@code FixedStoryProviderRegistrationTests}.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!prod")
public class FixedStoryProviderConfiguration {

	@Bean
	public FixedStoryScenarioLoader fixedStoryScenarioLoader(ObjectMapper objectMapper) {
		return new FixedStoryScenarioLoader(objectMapper);
	}

	@Bean
	public FixedStoryProvider fixedStoryProvider(FixedStoryScenarioLoader loader) {
		return new FixedStoryProvider(loader.load());
	}
}

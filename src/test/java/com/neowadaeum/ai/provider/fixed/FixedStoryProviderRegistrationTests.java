package com.neowadaeum.ai.provider.fixed;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 결정론 Provider 가 운영 등록 경로에 섞이지 않는다 (R3.1, I-14).
 *
 * <p>ADR-0004 "대체 수단의 안전 조건" 2. 이 사고는 조용하다 — 예외가 아니라 <b>고정된 이야기</b>가
 * 실제 사용자에게 나가는 형태로 나타난다. 그래서 애노테이션이 붙어 있다는 확인으로 갈음하지 않고
 * <b>컨텍스트에 빈이 없다는 동작</b>으로 검증한다 (PR 템플릿 보안 항목과 같은 기준).
 */
class FixedStoryProviderRegistrationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
			.withUserConfiguration(FixedStoryProviderConfiguration.class);

	/** R3.1 · I-14 — {@code prod} 에서는 빈이 만들어지지 않는다. */
	@Test
	void R3_1_fixed_story_provider_is_absent_under_the_prod_profile() {
		runner.withPropertyValues("spring.profiles.active=prod")
				.run(context -> assertThat(context).doesNotHaveBean(FixedStoryProvider.class));
	}

	/** {@code prod} 가 다른 프로파일과 함께 켜져도 닫혀 있어야 한다. */
	@Test
	void R3_1_prod_stays_closed_even_when_combined_with_another_profile() {
		runner.withPropertyValues("spring.profiles.active=prod,dev")
				.run(context -> assertThat(context).doesNotHaveBean(FixedStoryProvider.class));
	}

	/** 개발·테스트에서는 등록된다. 차단이 개발 편의까지 막으면 우회가 생긴다. */
	@Test
	void R3_1_fixed_story_provider_is_available_outside_prod() {
		runner.withPropertyValues("spring.profiles.active=dev")
				.run(context -> assertThat(context).hasSingleBean(FixedStoryProvider.class));
	}
}

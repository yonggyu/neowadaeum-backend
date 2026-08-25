package com.neowadaeum.ai.provider.fixed;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.ai.provider.StoryProvider;
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

	/**
	 * <b>프로파일을 하나도 지정하지 않으면 등록되지 않는다</b> (#47).
	 *
	 * <p>이 이슈의 본체다. {@code @Profile("!prod")} 는 여기서 <b>참</b>이라, 프로파일 지정을
	 * 빠뜨리고 뜬 인스턴스에 결정론 Provider 가 조용히 등록됐다. 차단이 "이름을 빠뜨리지 않는 것"에
	 * 의존하면 언젠가 빠뜨린다 — 없으면 안 켜지는 쪽이 기본값이어야 한다.
	 */
	@Test
	void R3_1_no_active_profile_means_no_fixed_story_provider() {
		runner.run(context -> {
			assertThat(context).doesNotHaveBean(FixedStoryProvider.class);
			// 감싼 것도 함께 없어야 한다. 델리게이트만 막고 래퍼가 남으면 배선이 깨질 뿐 경계는 그대로다.
			assertThat(context).doesNotHaveBean(StoryProvider.class);
		});
	}

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

	/**
	 * <b>명시적으로 켜는 경로</b> — {@code dev} 를 지정했을 때만 등록된다.
	 *
	 * <p>차단이 개발 편의까지 막으면 우회가 다른 형태로 생긴다. 슬라이스는 이 빈 위에서 돈다.
	 */
	@Test
	void R3_1_the_dev_profile_is_the_explicit_opt_in() {
		runner.withPropertyValues("spring.profiles.active=dev")
				.run(context -> {
					assertThat(context).hasSingleBean(FixedStoryProvider.class);
					// 시간 제한 래퍼와 @Primary 배선은 AiGatewayConfiguration 으로 옮겼다 (B-18).
					// 어댑터 구성이 하는 일은 자기 자신을 등록하는 것뿐이라 빈은 하나다.
					assertThat(context).hasSingleBean(StoryProvider.class);
				});
	}

	/**
	 * <b>{@code prod} 아닌 아무 프로파일로는 켜지지 않는다.</b>
	 *
	 * <p>{@code "!prod"} 와 {@code "dev & !prod"} 가 갈리는 지점이다. 전자는 여기서도 참이라,
	 * 새 프로파일이 생길 때마다 결정론 Provider 가 따라 들어갔다.
	 */
	@Test
	void R3_1_an_unrelated_profile_does_not_enable_it() {
		runner.withPropertyValues("spring.profiles.active=staging")
				.run(context -> assertThat(context).doesNotHaveBean(FixedStoryProvider.class));
	}
}

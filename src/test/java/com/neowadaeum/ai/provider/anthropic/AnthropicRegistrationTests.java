package com.neowadaeum.ai.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.ai.prompt.PromptConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * <b>설정이 갖춰졌을 때만 등록된다</b> (B-22, R3.1, §7.3).
 *
 * <p>키나 모델이 빠진 채 등록되면 그 사실이 <b>첫 턴 요청에서야</b> 드러나고, 그때 사용자는
 * 502 를 본다. 부팅 시점에 없는 편이 낫다 — 지목받았는데 없으면 {@code ProviderRegistry} 가
 * 부팅을 멈춘다 (B-18).
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class AnthropicRegistrationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(PromptConfiguration.class, AnthropicProviderConfiguration.class);

	/** 둘 다 있으면 등록된다. */
	@Test
	void R3_1_a_fully_configured_adapter_is_registered() {
		this.runner.withPropertyValues(
						"ai.providers.anthropic.api-key=test-key",
						"ai.providers.anthropic.model=claude-opus-5")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBeansOfType(AnthropicStoryProvider.class)).hasSize(1);
				});
	}

	/**
	 * <b>키가 없으면 등록되지 않는다</b> (§7.3).
	 *
	 * <p>{@code ${VAR:실제값}} 기본값을 두지 않는 것과 같은 이유다 — 값이 빠진 배포가 조용히
	 * 뜨면 안 된다.
	 */
	@Test
	void S7_3_a_missing_api_key_leaves_the_adapter_unregistered() {
		this.runner.withPropertyValues("ai.providers.anthropic.model=claude-opus-5")
				.run(context -> assertThat(context.getBeansOfType(AnthropicStoryProvider.class)).isEmpty());
	}

	/**
	 * <b>모델만 빠져도 등록되지 않는다.</b>
	 *
	 * <p>설정을 하다 만 상태이며, 그대로 등록되면 모델 없는 요청이 나간다.
	 */
	@Test
	void R3_1_a_missing_model_leaves_the_adapter_unregistered() {
		this.runner.withPropertyValues("ai.providers.anthropic.api-key=test-key")
				.run(context -> assertThat(context.getBeansOfType(AnthropicStoryProvider.class)).isEmpty());
	}

	/** 아무 설정도 없으면 등록되지 않는다 — 슬라이스의 현재 상태가 이것이다. */
	@Test
	void R3_1_no_configuration_means_no_adapter() {
		this.runner.run(context -> assertThat(context.getBeansOfType(AnthropicStoryProvider.class)).isEmpty());
	}

	/** 기본값은 코드가 갖는다 — 배포마다 정할 값이 아니다. */
	@Test
	void B22_base_url_and_max_tokens_have_code_defaults() {
		AnthropicProperties properties = new AnthropicProperties("key", "model", null, null);

		assertThat(properties.baseUrl()).isEqualTo("https://api.anthropic.com");
		assertThat(properties.maxTokens()).isEqualTo(4096);
		assertThat(properties.configured()).isTrue();
	}
}

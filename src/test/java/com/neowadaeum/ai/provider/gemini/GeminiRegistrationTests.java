package com.neowadaeum.ai.provider.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.ai.gateway.ProviderRegistry;
import com.neowadaeum.ai.log.AiCallRecorder;
import com.neowadaeum.ai.prompt.PromptConfiguration;
import com.neowadaeum.ai.provider.StoryProvider;
import com.neowadaeum.ai.schema.TurnOutputConfiguration;
import com.neowadaeum.config.SharedPropertiesConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Gemini 등록 경계 계약 (B-22-1, R3.1, §7.3). */
class GeminiRegistrationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(SharedPropertiesConfiguration.class, PromptConfiguration.class,
					TurnOutputConfiguration.class, RecorderConfiguration.class, GeminiProviderConfiguration.class);

	@Test
	void R3_1_a_fully_configured_adapter_is_registered() {
		this.runner.withPropertyValues("ai.providers.gemini.api-key=test-key",
				"ai.providers.gemini.models.turn=gemini-turn")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBeansOfType(GeminiStoryProvider.class)).hasSize(1);
					List<StoryProvider> providers = List.copyOf(
							context.getBeansOfType(GeminiStoryProvider.class).values());
					assertThat(new ProviderRegistry(providers)
							.select("gemini").providerId()).isEqualTo("gemini");
				});
	}

	@Test
	void S7_3_a_missing_api_key_leaves_the_adapter_unregistered() {
		this.runner.withPropertyValues("ai.providers.gemini.models.turn=gemini-turn")
				.run(context -> assertThat(context.getBeansOfType(GeminiStoryProvider.class)).isEmpty());
	}

	@Test
	void R3_1_a_missing_turn_model_leaves_the_adapter_unregistered() {
		this.runner.withPropertyValues("ai.providers.gemini.api-key=test-key")
				.run(context -> assertThat(context.getBeansOfType(GeminiStoryProvider.class)).isEmpty());
	}

	@Test
	void R3_6_each_purpose_keeps_its_own_model_slot() {
		this.runner.withPropertyValues("ai.providers.gemini.api-key=test-key",
				"ai.providers.gemini.models.turn=gemini-turn",
				"ai.providers.gemini.models.summary=gemini-summary",
				"ai.providers.gemini.models.safety=gemini-safety",
				"ai.providers.gemini.models.outline=gemini-outline")
				.run(context -> {
					GeminiProperties properties = context.getBean(GeminiProperties.class);
					assertThat(properties.models())
							.extracting(GeminiProperties.Models::turn, GeminiProperties.Models::summary,
									GeminiProperties.Models::safety, GeminiProperties.Models::outline)
							.containsExactly("gemini-turn", "gemini-summary", "gemini-safety", "gemini-outline");
				});
	}

	@Configuration(proxyBeanMethods = false)
	static class RecorderConfiguration {

		@Bean
		AiCallRecorder aiCallRecorder() {
			return draft -> {
			};
		}
	}
}

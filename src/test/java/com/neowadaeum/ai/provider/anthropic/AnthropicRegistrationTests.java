package com.neowadaeum.ai.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.ai.log.AiCallRecorder;
import com.neowadaeum.ai.prompt.PromptConfiguration;
import com.neowadaeum.config.SharedPropertiesConfiguration;
import com.neowadaeum.ai.provider.AiPurpose;
import com.neowadaeum.ai.provider.ProviderProperties;
import com.neowadaeum.ai.provider.StoryProvider;
import java.util.List;
import java.util.Optional;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
			.withUserConfiguration(SharedPropertiesConfiguration.class, PromptConfiguration.class,
					RecorderConfiguration.class, AnthropicProviderConfiguration.class);

	/** 둘 다 있으면 등록된다. */
	@Test
	void R3_1_a_fully_configured_adapter_is_registered() {
		this.runner.withPropertyValues(
						"ai.providers.anthropic.api-key=test-key",
						"ai.providers.anthropic.models.turn=claude-opus-5")
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
		this.runner.withPropertyValues("ai.providers.anthropic.models.turn=claude-opus-5")
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

	/**
	 * <b>소켓 읽기 상한은 생성 예산보다 항상 크다</b> (#93 점검, §13-19).
	 *
	 * <p>작거나 같으면 <b>상한이 먼저 끊고</b>, 시간 초과가 {@code 504} 가 아니라 {@code 502} 로
	 * 나간다 — 원인은 시간인데 표시는 벤더 장애가 된다. 상수로 고정해 두면 예산을 그보다 크게
	 * 잡는 순간 이 오분류가 조용히 생긴다.
	 */
	@Test
	void S13_19_the_socket_ceiling_never_preempts_the_generation_budget() {
		assertThat(AnthropicProviderConfiguration.socketReadCeiling(
						new ProviderProperties(Duration.ofSeconds(25), null)))
				.isGreaterThan(Duration.ofSeconds(25));

		assertThat(AnthropicProviderConfiguration.socketReadCeiling(
						new ProviderProperties(Duration.ofSeconds(90), null)))
				.as("예산을 90초로 올려도 상한이 먼저 끊으면 안 된다")
				.isGreaterThan(Duration.ofSeconds(90));
	}

	/**
	 * <b>{@code null} 을 돌려준 빈이 컬렉션 주입에도 나타나지 않는다</b> (#93 점검).
	 *
	 * <p>{@code getBeansOfType} 이 비어 있다는 것만으로는 부족하다. 실제 런타임 경로는
	 * {@code ProviderRegistry} 가 받는 <b>{@code List<StoryProvider>}</b> 이며, 거기에
	 * {@code null} 원소가 섞이면 증상은 등록이 아니라 <b>첫 턴 요청의 NPE</b> 로 나타난다.
	 *
	 * <p>스프링은 {@code NullBean} 을 주입 후보에서 제외한다 — 그 성질에 이 설계가 걸려 있으므로
	 * 여기서 못박는다.
	 */
	@Test
	void B22_a_null_bean_does_not_appear_in_collection_injection() {
		this.runner.withUserConfiguration(CollectingConfiguration.class)
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(Collected.class).providers())
							.as("null 원소가 섞이면 첫 턴 요청에서 NPE 가 된다")
							.isEmpty();
				});
	}

	/**
	 * 기록기는 이 테스트의 관심사가 아니다 (B-25).
	 *
	 * <p>실제 구현은 {@code promptlog} 리포지토리를 요구하고, 그것을 띄우면 <b>등록 경계를 보는
	 * 테스트가 JPA 배선까지 끌고 온다.</b> 여기서는 자리만 채운다.
	 */
	@Configuration(proxyBeanMethods = false)
	static class RecorderConfiguration {

		@Bean
		AiCallRecorder aiCallRecorder() {
			return draft -> {
			};
		}
	}

	/** 컬렉션 주입 지점을 흉내 낸다 — {@code ProviderRegistry} 가 이 형태로 받는다. */
	record Collected(List<StoryProvider> providers) {
	}

	@Configuration(proxyBeanMethods = false)
	static class CollectingConfiguration {

		@Bean
		Collected collected(Optional<List<StoryProvider>> providers) {
			return new Collected(providers.orElseGet(List::of));
		}
	}

	/**
	 * <b>R3.6 — 네 용도가 서로 다른 모델을 쓸 수 있다.</b> 이 작업의 DoD 다.
	 *
	 * <p>턴 생성은 고성능, 나머지는 저비용이라는 것이 요구사항의 문장이며, 그것을 <b>설정으로
	 * 표현할 수 있는가</b>가 여기서 확인된다.
	 */
	@Test
	void R3_6_each_purpose_can_use_a_different_model() {
		this.runner.withPropertyValues(
						"ai.providers.anthropic.api-key=test-key",
						"ai.providers.anthropic.models.turn=claude-opus-5",
						"ai.providers.anthropic.models.summary=claude-haiku-4-5",
						"ai.providers.anthropic.models.safety=claude-sonnet-5",
						"ai.providers.anthropic.models.outline=claude-haiku-4-5")
				.run(context -> {
					AnthropicProperties properties = context.getBean(AnthropicProperties.class);

					assertThat(properties.modelFor(AiPurpose.TURN)).isEqualTo("claude-opus-5");
					assertThat(properties.modelFor(AiPurpose.SUMMARY)).isEqualTo("claude-haiku-4-5");
					assertThat(properties.modelFor(AiPurpose.SAFETY)).isEqualTo("claude-sonnet-5");
					assertThat(properties.modelFor(AiPurpose.OUTLINE)).isEqualTo("claude-haiku-4-5");
				});
	}

	/**
	 * <b>설정되지 않은 용도는 조용히 다른 용도의 모델을 빌려 쓰지 않는다.</b>
	 *
	 * <p>빌려 쓰면 그 사고는 에러가 아니라 <b>비용 청구서</b>로 나타나고, 그때는 이미 한 달치다.
	 */
	@Test
	void R3_6_an_unconfigured_purpose_does_not_fall_back_to_another_model() {
		this.runner.withPropertyValues(
						"ai.providers.anthropic.api-key=test-key",
						"ai.providers.anthropic.models.turn=claude-opus-5")
				.run(context -> {
					AnthropicProperties properties = context.getBean(AnthropicProperties.class);

					assertThat(properties.modelFor(AiPurpose.TURN)).isEqualTo("claude-opus-5");
					assertThat(properties.modelFor(AiPurpose.SUMMARY)).isNull();
					assertThat(properties.modelFor(AiPurpose.SAFETY)).isNull();
					assertThat(properties.modelFor(AiPurpose.OUTLINE)).isNull();
				});
	}

	/**
	 * <b>턴 생성 모델이 없으면 등록되지 않는다.</b> 나머지 셋은 등록 조건이 아니다 —
	 * 요약(B-34) · 검수(B-30) · 아웃라인(B-52)은 아직 구현되지 않았다.
	 */
	@Test
	void R3_6_only_the_turn_model_gates_registration() {
		this.runner.withPropertyValues(
						"ai.providers.anthropic.api-key=test-key",
						"ai.providers.anthropic.models.summary=claude-haiku-4-5")
				.run(context -> assertThat(context.getBeansOfType(AnthropicStoryProvider.class)).isEmpty());
	}

	/** 기본값은 코드가 갖는다 — 배포마다 정할 값이 아니다. */
	@Test
	void B22_base_url_and_max_tokens_have_code_defaults() {
		AnthropicProperties properties = new AnthropicProperties("key", turnModel("model"), null, null, null);

		assertThat(properties.baseUrl()).isEqualTo("https://api.anthropic.com");
		assertThat(properties.maxTokens()).isEqualTo(4096);
		assertThat(properties.configured()).isTrue();
	}
	/** 턴 생성 모델만 채운 설정. 용도별 분리(B-24) 이후 대부분의 테스트가 필요로 하는 최소 형태다. */
	private static AnthropicProperties.Models turnModel(String model) {
		return new AnthropicProperties.Models(model, null, null, null);
	}

}

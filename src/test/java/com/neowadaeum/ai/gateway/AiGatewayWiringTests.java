package com.neowadaeum.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.ai.provider.OutlineRequest;
import com.neowadaeum.ai.provider.ProviderCapabilities;
import com.neowadaeum.ai.provider.SchemaRetryingStoryProvider;
import com.neowadaeum.ai.provider.StoryProvider;
import com.neowadaeum.ai.provider.SummaryRequest;
import com.neowadaeum.ai.provider.TimeLimitedStoryProvider;
import com.neowadaeum.ai.provider.TurnOnlyStoryProvider;
import com.neowadaeum.play.port.TurnRequest;
import com.neowadaeum.play.port.GeneratedTurn;
import com.neowadaeum.ai.schema.TurnOutputSchemaException;
import com.neowadaeum.play.port.GeneratedChoice;
import com.neowadaeum.play.port.GeneratedParagraph;
import com.neowadaeum.play.port.GenerationTimedOutException;
import com.neowadaeum.play.port.OutputSchemaRejectedException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * B-18 — <b>어느 Provider 가 불리는지를 설정이 정한다</b> (R3.1).
 *
 * <p>컨테이너가 필요 없다. 배선만 보는 테스트다 (ADR-0001).
 */
class AiGatewayWiringTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(AiGatewayConfiguration.class);

	private static TurnRequest request() {
		return TurnRequest.opening(UUID.randomUUID());
	}

	/**
	 * <b>이 작업의 DoD 다</b> — 같은 코드에 설정만 달리하면 선택이 바뀐다 (R3.1).
	 *
	 * <p>두 컨텍스트의 차이는 프로퍼티 한 줄뿐이다. "코드 배포 없이 전환"이 이것이다.
	 */
	@Test
	void R3_1_configuration_alone_decides_which_provider_is_used() {
		this.runner.withUserConfiguration(TwoAdapters.class)
				.withPropertyValues("ai.provider.active=alpha")
				.run(context -> assertThat(context.getBean(StoryProvider.class).providerId()).isEqualTo("alpha"));

		this.runner.withUserConfiguration(TwoAdapters.class)
				.withPropertyValues("ai.provider.active=beta")
				.run(context -> assertThat(context.getBean(StoryProvider.class).providerId()).isEqualTo("beta"));
	}

	/**
	 * <b>지목한 것이 등록되어 있지 않으면 부팅이 멈춘다.</b>
	 *
	 * <p>다른 어댑터로 조용히 넘어가면 운영에서 어느 모델이 도는지 아무도 모르는 상태가 된다.
	 * 그 사고는 에러가 아니라 "이상한 이야기"로 나타난다 (#72 와 같은 성질).
	 */
	@Test
	void R3_1_an_unknown_active_id_fails_the_boot() {
		this.runner.withUserConfiguration(TwoAdapters.class)
				.withPropertyValues("ai.provider.active=gamma")
				.run(context -> assertThat(context).hasFailed());
	}

	/** 어댑터가 둘인데 지목이 없으면 임의로 고르지 않는다. */
	@Test
	void R3_1_an_ambiguous_registration_is_not_resolved_silently() {
		this.runner.withUserConfiguration(TwoAdapters.class)
				.run(context -> assertThat(context).hasFailed());
	}

	/** 어댑터가 하나뿐이면 지목이 없어도 그것이다 — 슬라이스의 현재 상태가 이것이다. */
	@Test
	void R3_1_a_single_adapter_needs_no_active_setting() {
		this.runner.withUserConfiguration(OneAdapter.class)
				.run(context -> assertThat(context.getBean(StoryProvider.class).providerId()).isEqualTo("alpha"));
	}

	/** 어댑터가 하나도 없으면 뜨지 않는다. 고정된 이야기가 조용히 나가는 것보다 낫다 (#72). */
	@Test
	void R3_1_no_adapter_means_no_boot() {
		this.runner.run(context -> assertThat(context).hasFailed());
	}

	/**
	 * <b>게이트웨이가 자기 목록에 섞이지 않는다.</b>
	 *
	 * <p>{@link AiGateway} 도 {@code StoryProvider} 라서, 컬렉션 주입이 자기참조를 제외하지 않으면
	 * 색인이 자기를 가리키고 배선이 순환한다. 이 성질에 구성이 걸려 있으므로 못박아 둔다.
	 */
	@Test
	void B18_the_gateway_is_not_registered_as_one_of_its_own_adapters() {
		this.runner.withUserConfiguration(OneAdapter.class).run(context -> {
			assertThat(context).hasNotFailed();
			// 어댑터 1 + 게이트웨이 1. 게이트웨이가 색인에 들어갔다면 여기까지 오지 못한다.
			assertThat(context.getBeansOfType(StoryProvider.class)).hasSize(2);
			assertThat(context.getBean(StoryProvider.class)).isInstanceOf(AiGateway.class);
		});
	}

	/**
	 * <b>세션에 고정되는 것은 어댑터의 id 다</b> (I-4, R3.5).
	 *
	 * <p>{@code "gateway"} 를 저장하면 나중에 어느 벤더로 돌았는지 알 수 없다. 능력도 같다 —
	 * 앞단이 아니라 실제로 답하는 쪽의 것을 돌려준다.
	 */
	@Test
	void I4_the_session_records_the_adapter_id_not_the_gateway() {
		this.runner.withUserConfiguration(OneAdapter.class).run(context -> {
			StoryProvider gateway = context.getBean(StoryProvider.class);
			assertThat(gateway.providerId()).isEqualTo("alpha");
			assertThat(gateway.capabilities()).isEqualTo(ProviderCapabilities.withoutModel());
		});
	}

	/**
	 * R6.4 — 어느 어댑터를 골라도 시간 제한이 걸린다.
	 *
	 * <p>배선을 게이트웨이로 옮긴 이유가 이것이다. 어댑터 설정이 각자 감싸면 새 어댑터가 그것을
	 * 잊는다 (B-22).
	 */
	@Test
	void R6_4_the_selected_adapter_is_wrapped_with_the_time_limit() {
		this.runner.withUserConfiguration(SleepingAdapter.class)
				.withPropertyValues("ai.provider.timeout-ms=200")
				.run(context -> assertThatThrownBy(() -> context.getBean(StoryProvider.class).generateTurn(request()))
						.isInstanceOf(GenerationTimedOutException.class));
	}

	/**
	 * R5.8 — 어느 어댑터를 골라도 스키마 재요청이 걸린다 (B-21).
	 *
	 * <p>시간 제한과 같은 이유로 게이트웨이가 감싼다. 어댑터가 각자 세면 새 어댑터가 그것을 잊고,
	 * 잊은 사실은 사용자가 502 를 받은 뒤에 알게 된다.
	 */
	@Test
	void R5_8_the_selected_adapter_is_wrapped_with_the_schema_retry() {
		this.runner.withUserConfiguration(AlwaysViolatingAdapter.class).run(context -> {
			assertThatThrownBy(() -> context.getBean(StoryProvider.class).generateTurn(request()))
					.isInstanceOf(OutputSchemaRejectedException.class);

			assertThat(AlwaysViolatingAdapter.calls)
					.as("R5.8 — 최초 1회 + 재요청 1회. 게이트웨이를 지나며 재요청이 실제로 일어난다")
					.hasValue(2);
		});
	}

	/**
	 * 아직 구현되지 않은 용도는 게이트웨이를 지나도 예외 그대로다 (§0.2).
	 *
	 * <p>앞단이 생겼다는 이유로 {@code summarize} 가 조용히 뭔가를 돌려주기 시작하면 요약
	 * 파이프라인(B-34)이 붙기 전에도 초록이 된다.
	 */
	@Test
	void B18_unimplemented_uses_stay_unimplemented_through_the_gateway() {
		this.runner.withUserConfiguration(OneAdapter.class).run(context -> {
			StoryProvider gateway = context.getBean(StoryProvider.class);

			assertThatThrownBy(() -> gateway.summarize(
					new SummaryRequest(null, List.of(new SummaryRequest.TurnDigest(1, null, "요지")), 600)))
					.isInstanceOf(UnsupportedOperationException.class);
			assertThatThrownBy(() -> gateway.draftOutline(new OutlineRequest("세계관", 5, 3)))
					.isInstanceOf(UnsupportedOperationException.class);
		});
	}

	@Configuration(proxyBeanMethods = false)
	static class OneAdapter {

		@Bean
		EchoAdapter alpha() {
			return new EchoAdapter("alpha");
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class TwoAdapters {

		@Bean
		EchoAdapter alpha() {
			return new EchoAdapter("alpha");
		}

		@Bean
		EchoAdapter beta() {
			return new EchoAdapter("beta");
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class SleepingAdapter {

		@Bean
		EchoAdapter slow() {
			return new EchoAdapter("slow") {
				@Override
				public GeneratedTurn generateTurn(TurnRequest request) {
					try {
						Thread.sleep(5_000);
					}
					catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
					}
					return super.generateTurn(request);
				}
			};
		}
	}

	/** 언제나 스키마를 어기는 어댑터. 재요청이 게이트웨이를 지나며 실제로 걸리는지 센다. */
	@Configuration(proxyBeanMethods = false)
	static class AlwaysViolatingAdapter {

		static final AtomicInteger calls = new AtomicInteger();

		@Bean
		EchoAdapter violating() {
			calls.set(0);
			return new EchoAdapter("violating") {
				@Override
				public GeneratedTurn generateTurn(TurnRequest request) {
					calls.incrementAndGet();
					throw new TurnOutputSchemaException("paragraphs must be an array");
				}
			};
		}
	}

	/** 고정 응답만 돌려주는 어댑터. 배선을 보는 테스트라 응답 내용은 중요하지 않다. */
	static class EchoAdapter extends TurnOnlyStoryProvider {

		private final String id;

		EchoAdapter(String id) {
			this.id = id;
		}

		@Override
		public String providerId() {
			return this.id;
		}

		@Override
		public GeneratedTurn generateTurn(TurnRequest request) {
			return new GeneratedTurn(List.of(GeneratedParagraph.narration("본문")),
					List.of(new GeneratedChoice(1, "선택")),
					JsonMapper.builder().build().readTree("{}"), false, null);
		}
	}
}

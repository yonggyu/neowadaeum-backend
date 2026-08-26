package com.neowadaeum.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.ai.provider.AiCallFallback;
import com.neowadaeum.ai.provider.ProviderCapabilities;
import com.neowadaeum.ai.provider.StoryProvider;
import com.neowadaeum.ai.provider.TurnOnlyStoryProvider;
import com.neowadaeum.play.port.GeneratedChoice;
import com.neowadaeum.play.port.GeneratedParagraph;
import com.neowadaeum.play.port.GeneratedTurn;
import com.neowadaeum.play.port.GenerationContexts;
import com.neowadaeum.play.port.GenerationTimedOutException;
import com.neowadaeum.play.port.OutputSchemaRejectedException;
import com.neowadaeum.play.port.ProviderCallFailedException;
import com.neowadaeum.play.port.TurnRequest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * fallback 체인 (B-23, R3.7, ADR-0007).
 *
 * <p><b>여기서 고정하는 것은 "언제 넘기는가"다.</b> R3.7 은 <i>"provider 장애 시"</i> 라고만
 * 적었고, ADR-0007 이 그것을 네 갈래로 나눴다 — 넓게 넘기면 <b>한 턴의 비용과 시간 상한이 함께
 * 무너진다.</b>
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class FallbackChainTests {

	private static TurnRequest request() {
		return TurnRequest.opening(UUID.randomUUID(), GenerationContexts.populated());
	}

	private static GeneratedTurn answer() {
		return new GeneratedTurn(List.of(GeneratedParagraph.narration("본문")),
				List.of(new GeneratedChoice(1, "선택")),
				JsonMapper.builder().build().readTree("{}"), false, null);
	}

	/** 첫째가 답하면 나머지는 불리지 않는다. 승계 경로가 정상 경로를 건드리면 비용이 두 배가 된다. */
	@Test
	void R3_7_the_first_provider_answers_and_the_rest_are_not_called() {
		Counting primary = Counting.answering("anthropic");
		Counting secondary = Counting.answering("ollama");

		new FallbackChain(List.of(primary, secondary)).generateTurn(request());

		assertThat(primary.calls).isEqualTo(1);
		assertThat(secondary.calls).isZero();
	}

	/** R3.7 — 벤더가 죽으면 다음이 답한다. */
	@Test
	void R3_7_a_vendor_failure_is_taken_over_by_the_next_provider() {
		Counting primary = Counting.failingWith("anthropic", () -> new ProviderCallFailedException("down"));
		Counting secondary = Counting.answering("ollama");

		assertThat(new FallbackChain(List.of(primary, secondary)).generateTurn(request()).paragraphs()).hasSize(1);
		assertThat(secondary.calls).isEqualTo(1);
	}

	/**
	 * <b>R3.7 — 승계 시 원래 지목됐던 provider 가 기록에 남는다.</b>
	 *
	 * <p>남기지 않으면 <i>"왜 이 턴만 문체가 다른가"</i> 를 사후에 설명할 수 없다. 어댑터가
	 * 기록을 만들 때 이 값을 읽는다 ({@code fallback_from}).
	 */
	@Test
	void R3_7_the_intended_provider_is_visible_to_the_fallback_call() {
		Counting primary = Counting.failingWith("anthropic", () -> new ProviderCallFailedException("down"));
		Counting secondary = Counting.answering("ollama");

		new FallbackChain(List.of(primary, secondary)).generateTurn(request());

		assertThat(secondary.seenIntendedProvider)
				.as("승계된 호출이 원래 provider 를 모르면 fallback_from 이 비어 나간다")
				.isEqualTo("anthropic");
		assertThat(primary.seenIntendedProvider)
				.as("정상 경로는 승계가 아니다 — 값이 있으면 모든 호출이 승계로 기록된다")
				.isNull();
	}

	/**
	 * <b>시간 초과는 승계하지 않는다</b> (ADR-0007, §13-19).
	 *
	 * <p>예산을 이미 다 썼다. 넘기면 25초를 두 배로 쓰고 <b>클라이언트는 그 전에 끊는다</b> —
	 * 아무도 받지 못한 응답에 두 벤더가 청구한다.
	 */
	@Test
	void S13_19_a_timeout_is_not_taken_over() {
		Counting primary = Counting.failingWith("anthropic",
				() -> new GenerationTimedOutException(Duration.ofSeconds(25)));
		Counting secondary = Counting.answering("ollama");

		assertThatThrownBy(() -> new FallbackChain(List.of(primary, secondary)).generateTurn(request()))
				.isInstanceOf(GenerationTimedOutException.class);

		assertThat(secondary.calls).as("예산을 다 쓴 뒤에 한 번 더 불렀다").isZero();
	}

	/**
	 * <b>스키마 소진도 승계하지 않는다</b> (ADR-0007).
	 *
	 * <p>이미 R5.8 · R3.3 의 재요청을 다 쓴 뒤다. 거기에 다른 provider 호출을 더하면
	 * <b>한 턴의 비용 상한이 사라진다.</b>
	 */
	@Test
	void R5_8_an_exhausted_schema_retry_is_not_taken_over() {
		Counting primary = Counting.failingWith("anthropic",
				() -> new OutputSchemaRejectedException(2, null));
		Counting secondary = Counting.answering("ollama");

		assertThatThrownBy(() -> new FallbackChain(List.of(primary, secondary)).generateTurn(request()))
				.isInstanceOf(OutputSchemaRejectedException.class);

		assertThat(secondary.calls).isZero();
	}

	/**
	 * <b>I-4 — 세션에 고정되는 것은 체인의 첫째다</b> (ADR-0007).
	 *
	 * <p>승계가 일어나도 이 값은 바뀌지 않는다. 바뀌면 <b>한 번의 장애가 세션의 나머지를 영구히
	 * 옮긴다.</b>
	 */
	@Test
	void I4_the_pinned_provider_id_is_always_the_head_of_the_chain() {
		FallbackChain chain = new FallbackChain(
				List.of(Counting.failingWith("anthropic", () -> new ProviderCallFailedException("down")),
						Counting.answering("ollama")));

		chain.generateTurn(request());

		assertThat(chain.providerId())
				.as("승계 후 세션의 provider 가 바뀌면 그때부터 진짜 I-4 위반이다")
				.isEqualTo("anthropic");
	}

	/** 전부 죽으면 마지막 실패를 올린다. 삼키면 호출자가 빈 결과를 받는다. */
	@Test
	void R3_7_when_every_provider_fails_the_failure_surfaces() {
		assertThatThrownBy(() -> new FallbackChain(
				List.of(Counting.failingWith("anthropic", () -> new ProviderCallFailedException("down")),
						Counting.failingWith("ollama", () -> new ProviderCallFailedException("down"))))
				.generateTurn(request()))
				.isInstanceOf(ProviderCallFailedException.class);
	}

	/**
	 * <b>판정도 승계된다</b> (B-30).
	 *
	 * <p>요약·아웃라인과 갈리는 이유는 실패의 결과가 다르기 때문이다 — 판정하지 못한 응답은
	 * 통과하지 못하므로(fail-closed), 벤더 하나가 죽으면 <b>모든 턴이 차단된다.</b> 그것은
	 * ADR-0007 이 승계를 허용한 조건 그 자체다.
	 */
	@Test
	void B30_a_dead_vendor_does_not_block_every_turn_the_chain_classifies_instead() {
		Counting dead = Counting.failingWith("dead", () -> new ProviderCallFailedException("down"));
		Counting alive = Counting.answering("alive");

		java.util.Set<com.neowadaeum.common.spi.SafetyCategory> verdict =
				new FallbackChain(List.of(dead, alive))
						.classifySafety(new com.neowadaeum.common.spi.SafetyClassificationRequest(List.of("문장")));

		assertThat(verdict).isEmpty();
		assertThat(alive.calls).as("승계가 일어나지 않았다").isEqualTo(1);
		assertThat(alive.seenIntendedProvider)
				.as("승계 사실이 기록에 남으려면 원래 지목된 provider 를 알아야 한다 (R3.7)")
				.isEqualTo("dead");
	}

	/** 호출 횟수와 승계 여부를 기록하는 어댑터. */
	private static final class Counting extends TurnOnlyStoryProvider {

		private final String id;

		private final Supplier<RuntimeException> failure;

		private int calls;

		private String seenIntendedProvider;

		private Counting(String id, Supplier<RuntimeException> failure) {
			this.id = id;
			this.failure = failure;
		}

		static Counting answering(String id) {
			return new Counting(id, null);
		}

		static Counting failingWith(String id, Supplier<RuntimeException> failure) {
			return new Counting(id, failure);
		}

		@Override
		public String providerId() {
			return this.id;
		}

		@Override
		public ProviderCapabilities capabilities() {
			return ProviderCapabilities.withoutModel();
		}

		@Override
		public GeneratedTurn generateTurn(TurnRequest request) {
			this.calls++;
			this.seenIntendedProvider = AiCallFallback.intendedProviderId();
			if (this.failure != null) {
				throw this.failure.get();
			}
			return answer();
		}

		@Override
		public java.util.Set<com.neowadaeum.common.spi.SafetyCategory> classifySafety(
				com.neowadaeum.common.spi.SafetyClassificationRequest request) {
			this.calls++;
			this.seenIntendedProvider = AiCallFallback.intendedProviderId();
			if (this.failure != null) {
				throw this.failure.get();
			}
			return java.util.Set.of();
		}
	}
}

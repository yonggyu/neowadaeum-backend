package com.neowadaeum.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.ai.schema.TurnOutputSchemaException;
import com.neowadaeum.play.port.GenerationContexts;
import com.neowadaeum.play.port.GeneratedChoice;
import com.neowadaeum.play.port.GeneratedParagraph;
import com.neowadaeum.play.port.GeneratedTurn;
import com.neowadaeum.play.port.OutputSchemaRejectedException;
import com.neowadaeum.play.port.TurnRequest;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 스키마 위반 재요청 (B-21, R5.8 · R3.3).
 *
 * <p><b>세는 것은 호출 횟수다.</b> "결국 성공했다"만 보면 <b>몇 번을 불러 성공했는지</b>가 빠지고,
 * 그 숫자가 곧 청구액이다.
 */
class SchemaRetryingStoryProviderTests {

	private static TurnRequest request() {
		return TurnRequest.opening(UUID.randomUUID(), GenerationContexts.sample());
	}

	private static GeneratedTurn answer() {
		return new GeneratedTurn(List.of(GeneratedParagraph.narration("본문")),
				List.of(new GeneratedChoice(1, "선택")),
				JsonMapper.builder().build().readTree("{}"), false, null);
	}

	/** 정상 응답은 한 번에 끝난다. 재요청 경로가 정상 경로를 건드리면 비용이 두 배가 된다. */
	@Test
	void R5_8_a_valid_answer_is_not_requested_twice() {
		CountingProvider provider = CountingProvider.structured();

		assertThat(new SchemaRetryingStoryProvider(provider).generateTurn(request()).paragraphs().getFirst().text()).isEqualTo("본문");
		assertThat(provider.calls()).isEqualTo(1);
	}

	/** R5.8 — 한 번 어긋나면 다시 요청하고, 두 번째가 맞으면 그것을 돌려준다. */
	@Test
	void R5_8_one_schema_violation_is_retried_once_and_succeeds() {
		CountingProvider provider = CountingProvider.structured().thenViolate(1);

		assertThat(new SchemaRetryingStoryProvider(provider).generateTurn(request()).paragraphs().getFirst().text()).isEqualTo("본문");
		assertThat(provider.calls()).isEqualTo(2);
	}

	/**
	 * R5.8 — 재요청까지 실패하면 seam 예외다. 호출자는 이것을 {@code 502 PROVIDER_ERROR} 로 바꾼다.
	 *
	 * <p><b>세 번 부르지 않는다.</b> 구조화 출력을 지원하는 Provider 가 두 번 연속 어긋났다면
	 * 세 번째도 어긋난다 — 그 호출은 사용자를 기다리게 하고 비용만 쓴다.
	 */
	@Test
	void R5_8_a_structured_provider_stops_after_one_retry() {
		CountingProvider provider = CountingProvider.structured().thenViolate(Integer.MAX_VALUE);
		SchemaRetryingStoryProvider retrying = new SchemaRetryingStoryProvider(provider);

		assertThatThrownBy(() -> retrying.generateTurn(request()))
				.isInstanceOf(OutputSchemaRejectedException.class);

		assertThat(provider.calls()).as("R5.8 — 최초 1회 + 재요청 1회").isEqualTo(2);
	}

	/**
	 * R3.3 — {@code structuredOutput == false} 인 Provider 는 <b>2회까지</b> 재요청한다.
	 *
	 * <p>스키마를 강제할 수단이 프롬프트밖에 없으므로 한 번 더 준다.
	 */
	@Test
	void R3_3_an_unstructured_provider_retries_twice() {
		CountingProvider provider = CountingProvider.unstructured().thenViolate(Integer.MAX_VALUE);
		SchemaRetryingStoryProvider retrying = new SchemaRetryingStoryProvider(provider);

		assertThatThrownBy(() -> retrying.generateTurn(request()))
				.isInstanceOf(OutputSchemaRejectedException.class);

		assertThat(provider.calls()).as("R3.3 — 최초 1회 + 재요청 2회").isEqualTo(3);
	}

	/** R3.3 — 두 번째 재요청에서 맞으면 성공이다. 여분의 기회가 실제로 쓰인다. */
	@Test
	void R3_3_an_unstructured_provider_succeeds_on_the_third_attempt() {
		CountingProvider provider = CountingProvider.unstructured().thenViolate(2);

		assertThat(new SchemaRetryingStoryProvider(provider).generateTurn(request()).paragraphs().getFirst().text()).isEqualTo("본문");
		assertThat(provider.calls()).isEqualTo(3);
	}

	/**
	 * <b>스키마 위반만 다시 요청한다.</b> 시간 초과·연결 실패는 같은 요청을 다시 보내 나아지는
	 * 종류의 실패가 아니고, 시간 초과는 이미 25s 를 쓴 뒤다 (R6.4).
	 */
	@Test
	void R6_4_a_non_schema_failure_is_not_retried() {
		CountingProvider provider = CountingProvider.structured().thenFailWith(
				new IllegalStateException("connection reset"));
		SchemaRetryingStoryProvider retrying = new SchemaRetryingStoryProvider(provider);

		assertThatThrownBy(() -> retrying.generateTurn(request()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("connection reset");

		assertThat(provider.calls()).isEqualTo(1);
	}

	/** 감싸도 세션에 고정되는 값은 어댑터의 것이다 (I-4, R3.5). */
	@Test
	void I4_the_decorator_does_not_change_the_provider_identity() {
		SchemaRetryingStoryProvider retrying = new SchemaRetryingStoryProvider(CountingProvider.unstructured());

		assertThat(retrying.providerId()).isEqualTo("counting");
		assertThat(retrying.capabilities().structuredOutput()).isFalse();
	}

	/**
	 * S-3 — 예외는 로그로 흐른다. 응답 원문이 메시지에 실리면 막으려던 것이 로그로 나간다.
	 *
	 * <p>"있어야 할 것"만 단언하면 값이 새어도 통과한다 ({@code .claude/rules/testing.md}).
	 */
	@Test
	void S3_the_exhausted_exception_does_not_carry_the_response_body() {
		String secretish = "유나의 연락처는 010-0000-0000 이다";
		CountingProvider provider = CountingProvider.structured().thenViolate(Integer.MAX_VALUE);

		assertThatThrownBy(() -> new SchemaRetryingStoryProvider(provider).generateTurn(request()))
				.isInstanceOf(OutputSchemaRejectedException.class)
				.hasMessageContaining("did not match the turn schema")
				.hasMessageNotContaining(secretish)
				.rootCause()
				.hasMessageNotContaining(secretish);
	}

	/**
	 * <b>{@code summarize} · {@code draftOutline} 에는 재요청을 걸지 않는다.</b>
	 *
	 * <p>요약은 문자열이라 맞출 스키마가 없고, 아웃라인의 출력 계약은 B-52 가 정한다. 지금 없는
	 * 계약을 위해 빈 재요청을 걸어 두면 그 경로의 실패가 조용히 두 배로 청구된다 (§0.2).
	 */
	@Test
	void S0_2_the_other_seam_methods_are_passed_straight_through() {
		SchemaRetryingStoryProvider retrying = new SchemaRetryingStoryProvider(CountingProvider.structured());

		assertThatThrownBy(() -> retrying.summarize(null)).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> retrying.draftOutline(null)).isInstanceOf(UnsupportedOperationException.class);
	}

	/** 호출 횟수를 세고, 정해진 횟수만큼 미리 실패시키는 어댑터. */
	private static final class CountingProvider extends TurnOnlyStoryProvider {

		private final boolean structuredOutput;
		private final AtomicInteger calls = new AtomicInteger();
		private final Deque<RuntimeException> failures = new ArrayDeque<>();

		private CountingProvider(boolean structuredOutput) {
			this.structuredOutput = structuredOutput;
		}

		static CountingProvider structured() {
			return new CountingProvider(true);
		}

		static CountingProvider unstructured() {
			return new CountingProvider(false);
		}

		/** 앞의 {@code times} 회를 스키마 위반으로 만든다. */
		CountingProvider thenViolate(int times) {
			for (int i = 0; i < Math.min(times, 8); i++) {
				this.failures.add(new TurnOutputSchemaException("paragraphs must be an array"));
			}
			return this;
		}

		/** 첫 호출을 이 예외로 실패시킨다. */
		CountingProvider thenFailWith(RuntimeException failure) {
			this.failures.add(failure);
			return this;
		}

		int calls() {
			return this.calls.get();
		}

		@Override
		public String providerId() {
			return "counting";
		}

		@Override
		public ProviderCapabilities capabilities() {
			return new ProviderCapabilities(this.structuredOutput, 0, false);
		}

		@Override
		public GeneratedTurn generateTurn(TurnRequest ignored) {
			this.calls.incrementAndGet();
			RuntimeException failure = this.failures.poll();
			if (failure != null) {
				throw failure;
			}
			return answer();
		}
	}
}

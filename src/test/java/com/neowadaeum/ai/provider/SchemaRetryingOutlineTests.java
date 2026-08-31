package com.neowadaeum.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.ai.schema.OutlineOutputSchemaException;
import com.neowadaeum.play.port.GeneratedTurn;
import com.neowadaeum.play.port.GenerationTimedOutException;
import com.neowadaeum.play.port.TurnRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 초안 재요청 (#238, B-52).
 *
 * <p><b>초안은 1회다.</b> 턴의 1~2회 규칙(R5.8 · R3.3)은 §5.2 의 턴 출력 스키마에 대한 것이고,
 * 초안 계약은 그보다 훨씬 작다 — 두 번 놓치는 모델에게 세 번째를 주는 근거가 없다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class SchemaRetryingOutlineTests {

	private static final OutlineRequest REQUEST = new OutlineRequest("봄의 학교", 5, 3);

	private static final OutlineResult DRAFT = new OutlineResult(
			List.of(new OutlineResult.Chapter("첫 장", "무슨 일이 일어난다.")), List.of());

	/** 계약을 어긴 응답은 <b>한 번</b> 다시 요청한다. */
	@Test
	void B52_a_violation_is_retried_once() {
		CountingProvider delegate = new CountingProvider(1);

		OutlineResult result = withBudget(
				() -> new SchemaRetryingStoryProvider(delegate).draftOutline(REQUEST));

		assertThat(result).isEqualTo(DRAFT);
		assertThat(delegate.calls).isEqualTo(2);
	}

	/**
	 * <b>두 번째도 어기면 실패다.</b> 세 번째는 없다 — 빈 초안을 돌려주지 않는다.
	 */
	@Test
	void B52_a_second_violation_is_not_retried_again() {
		CountingProvider delegate = new CountingProvider(2);

		assertThatThrownBy(() -> withBudget(
				() -> new SchemaRetryingStoryProvider(delegate).draftOutline(REQUEST)))
				.isInstanceOf(OutlineOutputSchemaException.class);
		assertThat(delegate.calls).isEqualTo(2);
	}

	/**
	 * <b>재요청은 별개의 호출로 기록된다</b> (B-25, R5.8).
	 *
	 * <p>한 행으로 합치면 <b>형식을 자주 놓치는 모델의 비용</b>이 통계에서 사라진다.
	 */
	@Test
	void B25_each_attempt_is_recorded_under_its_own_number() {
		CountingProvider delegate = new CountingProvider(1);

		withBudget(() -> new SchemaRetryingStoryProvider(delegate).draftOutline(REQUEST));

		assertThat(delegate.attempts).containsExactly(1, 2);
	}

	/**
	 * <b>예산이 없으면 재요청하지 않는다</b> (B-21-2, §13-19).
	 *
	 * <p>이미 끝난 예산으로 유료 호출을 거는 것보다 걸지 않는 편이 싸다.
	 */
	@Test
	void B52_an_exhausted_budget_stops_the_retry() {
		CountingProvider delegate = new CountingProvider(1);
		GenerationBudget spent = GenerationBudget.startingNow(
				Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), Duration.ZERO);

		assertThatThrownBy(() -> inBudget(spent,
				() -> new SchemaRetryingStoryProvider(delegate).draftOutline(REQUEST)))
				.isInstanceOf(GenerationTimedOutException.class);
		assertThat(delegate.calls).isEqualTo(1);
	}

	/** 예산이 열린 채로 실행한다 — 시간 제한 데코레이터가 하는 일과 같다. */
	private static OutlineResult withBudget(java.util.function.Supplier<OutlineResult> call) {
		return inBudget(GenerationBudget.startingNow(Clock.systemUTC(), Duration.ofSeconds(25)), call);
	}

	/**
	 * {@code within} 은 {@link Exception} 을 선언한다 (임의의 {@code Callable} 을 받으므로).
	 *
	 * <p>여기서 도는 것은 런타임 예외만 던지므로, 검사 예외를 테스트마다 옮겨 적는 대신 한 곳에서
	 * 감싼다 — <b>기대하는 예외가 {@code Exception} 에 섞이면 테스트가 무엇을 봤는지 흐려진다.</b>
	 */
	private static OutlineResult inBudget(GenerationBudget budget,
			java.util.function.Supplier<OutlineResult> call) {
		try {
			return GenerationBudget.within(budget, call::get);
		}
		catch (RuntimeException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** 앞의 {@code violations} 번은 계약을 어기고 그 뒤로는 초안을 돌려준다. */
	private static final class CountingProvider extends TurnOnlyStoryProvider {

		private final int violations;

		private int calls;

		private final List<Integer> attempts = new ArrayList<>();

		private CountingProvider(int violations) {
			this.violations = violations;
		}

		@Override
		public String providerId() {
			return "counting";
		}

		@Override
		public GeneratedTurn generateTurn(TurnRequest request) {
			throw new UnsupportedOperationException("이 테스트는 턴을 만들지 않는다");
		}

		@Override
		public OutlineResult draftOutline(OutlineRequest request) {
			this.attempts.add(AiCallAttempt.current());
			if (++this.calls <= this.violations) {
				throw new OutlineOutputSchemaException("outline response is not json");
			}
			return DRAFT;
		}
	}
}

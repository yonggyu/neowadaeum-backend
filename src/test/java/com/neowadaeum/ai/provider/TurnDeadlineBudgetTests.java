package com.neowadaeum.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.common.support.TurnDeadline;
import com.neowadaeum.play.port.GeneratedChoice;
import com.neowadaeum.play.port.GeneratedParagraph;
import com.neowadaeum.play.port.GeneratedTurn;
import com.neowadaeum.play.port.GenerationContexts;
import com.neowadaeum.play.port.GenerationTimedOutException;
import com.neowadaeum.play.port.TurnRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * #116 — <b>턴 하나의 외부 호출 총합에 상한이 있는가</b> (§6.3, R6.4).
 *
 * <p>B-30 이 판정을 붙이면서 한 턴의 외부 호출이 최대 넷이 됐다 — 생성 · 판정 · 재생성 · 재판정.
 * 각각에 상한이 있었지만 <b>넷을 합친 값에는 상한이 없었다.</b> 여기서 확인하는 것은 그 합이
 * 값으로 강제되는가다.
 *
 * <p><b>{@code Thread.sleep} 을 쓰지 않는다</b> (#116 DoD). 시계를 손으로 밀어 "예산의 대부분을
 * 쓴 뒤"를 만든다 — 실제로 27초를 기다리면 이 경로는 아무도 검증하지 않게 된다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class TurnDeadlineBudgetTests {

	private static final Instant START = Instant.parse("2026-08-27T00:00:00Z");

	/** §6.3 의 동기 응답 예산. */
	private static final Duration TURN_BUDGET = Duration.ofSeconds(28);

	/** §6.3 의 Provider 상한. 한 호출의 몫이다. */
	private static final Duration CALL_LIMIT = Duration.ofSeconds(25);

	private final MovableClock clock = new MovableClock(START);

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	private final List<Duration> allowances = new ArrayList<>();

	@AfterEach
	void shutdown() {
		this.executor.shutdownNow();
	}

	/**
	 * 위임이 도는 동안 <b>그 호출에 허용된 시간</b>을 기록한다.
	 *
	 * <p>{@code GenerationBudget.current()} 이 그 값을 들고 있다 — 시간 제한 데코레이터가 연 것이며,
	 * 남은 턴 예산과 자기 상한 중 작은 쪽이다.
	 */
	private StoryProvider recording(Duration consumesPerCall) {
		return new TurnOnlyStoryProvider() {
			@Override
			public String providerId() {
				return "recording";
			}

			@Override
			public GeneratedTurn generateTurn(TurnRequest request) {
				TurnDeadlineBudgetTests.this.allowances.add(GenerationBudget.current().total());
				TurnDeadlineBudgetTests.this.clock.advance(consumesPerCall);
				return answer();
			}
		};
	}

	private TimeLimitedStoryProvider limited(StoryProvider delegate) {
		return new TimeLimitedStoryProvider(delegate, this.executor, CALL_LIMIT, this.clock);
	}

	/** 턴 예산이 열려 있지 않으면 각 호출은 <b>자기 상한만</b> 지킨다 — 요약(R4.6)이 그 경우다. */
	@Test
	void S6_3_without_a_turn_deadline_each_call_keeps_its_own_limit() {
		limited(recording(Duration.ZERO)).generateTurn(request());

		assertThat(this.allowances).containsExactly(CALL_LIMIT);
	}

	/**
	 * <b>턴 예산이 자기 상한보다 짧으면 그쪽이 이긴다.</b>
	 *
	 * <p>28초 예산이 열린 직후의 호출은 25초를 받는다(자기 상한이 더 짧다). 그러나 앞 단계가
	 * 20초를 쓰고 나면 남은 것은 8초이고, 그때는 <b>28도 25도 아닌 8</b> 이 상한이다.
	 */
	@Test
	void S6_3_the_shorter_of_the_two_wins() {
		StoryProvider provider = recording(Duration.ofSeconds(20));

		TurnDeadline.within(TurnDeadline.startingNow(this.clock, TURN_BUDGET), () -> {
			limited(provider).generateTurn(request());
			limited(provider).generateTurn(request());
			return null;
		});

		assertThat(this.allowances).containsExactly(CALL_LIMIT, Duration.ofSeconds(8));
	}

	/**
	 * <b>합이 예산을 넘지 않는다</b> (#116 DoD).
	 *
	 * <p>호출 넷이 각각 10초를 쓰면 자기 상한(25초)으로는 전부 통과한다 — 그것이 이 이슈가 지적한
	 * 상태다. 턴 예산이 열려 있으면 <b>합이 28초에 닿는 순간</b> 다음 호출이 걸리지 않는다.
	 */
	@Test
	void S6_3_four_calls_cannot_exceed_the_turn_budget() {
		StoryProvider provider = recording(Duration.ofSeconds(10));

		assertThatThrownBy(() -> TurnDeadline.within(TurnDeadline.startingNow(this.clock, TURN_BUDGET), () -> {
			for (int call = 0; call < 4; call++) {
				limited(provider).generateTurn(request());
			}
			return null;
		})).isInstanceOf(GenerationTimedOutException.class);

		// 10 + 10 = 20초까지는 돈다. 세 번째는 남은 8초를 받고 그 안에 끝나 30초가 되며,
		// 네 번째는 남은 예산이 0 이라 아예 걸리지 않는다.
		assertThat(this.allowances).hasSize(3);
		assertThat(this.clock.instant()).isEqualTo(START.plusSeconds(30));
	}

	/**
	 * <b>남은 예산이 없으면 호출이 발생하지 않는다</b> (#116 DoD — 호출 횟수로 확인).
	 *
	 * <p>이미 끝난 예산으로 호출을 걸어 놓고 취소하는 것보다 걸지 않는 편이 싸다. 취소는
	 * 인터럽트로 닿지만, <b>그때는 이미 어댑터가 한 번 시작한 뒤</b>다.
	 */
	@Test
	void S6_3_an_exhausted_budget_does_not_start_a_call() {
		StoryProvider provider = recording(Duration.ZERO);
		TurnDeadline deadline = TurnDeadline.startingNow(this.clock, TURN_BUDGET);
		this.clock.advance(TURN_BUDGET);

		assertThatThrownBy(() -> TurnDeadline.within(deadline, () -> limited(provider).generateTurn(request())))
				.isInstanceOf(GenerationTimedOutException.class);

		assertThat(this.allowances).as("예산이 없으면 위임은 시작되지도 않는다").isEmpty();
	}

	/** 예산은 중첩돼도 복원된다 — 요약이 턴 안에서 도는 배선이 생겨도 서로를 덮지 않는다. */
	@Test
	void S6_3_a_nested_deadline_is_restored() {
		TurnDeadline outer = TurnDeadline.startingNow(this.clock, TURN_BUDGET);

		Duration afterNesting = TurnDeadline.within(outer, () -> {
			TurnDeadline.within(TurnDeadline.startingNow(this.clock, Duration.ofSeconds(1)), () -> null);
			return TurnDeadline.allowedFor(CALL_LIMIT);
		});

		assertThat(afterNesting).isEqualTo(CALL_LIMIT);
		assertThat(TurnDeadline.allowedFor(CALL_LIMIT)).as("밖에서는 다시 열려 있지 않다").isEqualTo(CALL_LIMIT);
		assertThat(TurnDeadline.exhausted()).isFalse();
	}

	private static TurnRequest request() {
		return TurnRequest.opening(UUID.randomUUID(), GenerationContexts.sample());
	}

	private static GeneratedTurn answer() {
		return new GeneratedTurn(List.of(GeneratedParagraph.narration("본문")),
				List.of(new GeneratedChoice(1, "선택")),
				JsonMapper.builder().build().readTree("{}"), false, null);
	}

	/** 손으로 미는 시계. {@code sleep} 없이 "예산의 대부분을 쓴 뒤"를 만든다. */
	private static final class MovableClock extends Clock {

		private Instant now;

		private MovableClock(Instant now) {
			this.now = now;
		}

		void advance(Duration amount) {
			this.now = this.now.plus(amount);
		}

		@Override
		public Instant instant() {
			return this.now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}
	}
}

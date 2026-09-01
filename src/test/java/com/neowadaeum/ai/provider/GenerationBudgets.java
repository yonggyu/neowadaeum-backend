package com.neowadaeum.ai.provider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.function.Supplier;

/**
 * 예산을 여는 테스트 지원 (B-21-2).
 *
 * <p><b>시간을 기다리지 않고 민다.</b> "남은 예산이 3초일 때 재요청이 어떻게 되는가" 를 실제로
 * 22초를 보낸 뒤에 보는 테스트는 파이프라인을 지배한다 (ADR-0001). 시계를 손으로 밀면 같은 코드
 * 경로를 밀리초 안에 지난다 — I-15 가 게임 로직에 요구하는 결정론과 같은 성질이다.
 */
public final class GenerationBudgets {

	public static final Instant START = Instant.parse("2026-08-26T00:00:00Z");

	private GenerationBudgets() {
	}

	/** 예산을 열고 그 안에서 실행한다. 검사 예외를 테스트마다 다시 포장하지 않으려고 감싼다. */
	public static <T> T within(GenerationBudget budget, Supplier<T> body) {
		try {
			return GenerationBudget.within(budget, body::get);
		}
		catch (RuntimeException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * 계약값 크기의 예산을 실제 시계로 열고 실행한다.
	 *
	 * <p>남은 예산이 몇 초인지가 관심사가 <b>아닌</b> 테스트를 위한 것이다 — 어댑터 계약 테스트는
	 * 재요청이 몇 번 걸리는지를 보며, 그것을 보려면 예산이 열려 있기만 하면 된다.
	 */
	public static <T> T withinContractBudget(Supplier<T> body) {
		return within(GenerationBudget.startingNow(Clock.systemUTC(), ProviderProperties.CONTRACT_TIMEOUT), body);
	}

	/** 손으로 미는 시계. {@link Clock#instant()} 만 쓰므로 나머지는 고정 오프셋이면 충분하다. */
	public static final class TickingClock extends Clock {

		private Instant now = START;

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return this.now;
		}

		public void advance(Duration elapsed) {
			this.now = this.now.plus(elapsed);
		}
	}
}

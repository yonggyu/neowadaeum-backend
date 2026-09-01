package com.neowadaeum.common.support;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

/**
 * 턴 하나의 서버 응답 예산 (§6.3, #116).
 *
 * <p>§6.3 은 <b>Provider 25초 / 동기 응답 28초</b>를 정한다. 앞의 값은
 * {@code ai.provider.timeout-ms} 이고 뒤의 값이 여기다 — <b>두 값은 서로 다른 것을 가리키므로
 * 하나로 합칠 수 없다.</b> 하나는 호출 하나의 상한이고, 하나는 그 호출들을 전부 합친 상한이다.
 *
 * <p><b>{@code common} 이 소유한다</b> (§5.4, #97 과 같은 근거). 예산을 여는 것은 {@code play} 이고
 * 그것을 읽는 것은 {@code ai} 다 — 어느 한쪽에 두면 다른 쪽이 참조할 수 없다 (ADR-0006).
 *
 * <p>설정으로 둔 이유는 B-46 이 p95 를 실측한 뒤 조정할 값이기 때문이다. 코드 상수로 박아 두면
 * 그 조정이 배포가 된다.
 */
@ConfigurationProperties("play.turn")
public record TurnBudgetProperties(@DurationUnit(ChronoUnit.MILLIS) Duration budgetMs) {

	/** §6.3 이 정한 동기 응답 상한. 설정이 없으면 계약값을 쓴다. */
	private static final Duration CONTRACT_BUDGET = Duration.ofSeconds(28);

	public TurnBudgetProperties {
		if (budgetMs == null) {
			budgetMs = CONTRACT_BUDGET;
		}
		if (budgetMs.isNegative() || budgetMs.isZero()) {
			throw new IllegalArgumentException("play.turn.budget-ms must be positive");
		}
	}

	/** 계약값 그대로. 설정을 띄우지 않는 테스트가 쓴다 ({@code RecentTurnsProperties.defaults()} 와 같다). */
	public static TurnBudgetProperties defaults() {
		return new TurnBudgetProperties(CONTRACT_BUDGET);
	}
}

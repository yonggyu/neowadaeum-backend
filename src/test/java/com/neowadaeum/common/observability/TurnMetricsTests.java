package com.neowadaeum.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * B-48 — 턴이 얼마나 걸렸고 어떻게 끝났는지 (§12).
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class TurnMetricsTests {

	private final MeterRegistry registry = new SimpleMeterRegistry();

	private final TurnMetrics metrics = new TurnMetrics(this.registry);

	/** <b>차단도 하나의 결과다.</b> 실패로 세면 에러율과 섞인다. */
	@Test
	void B48_a_blocked_turn_is_an_outcome_not_an_error() {
		this.metrics.recordTurn("generated", Duration.ofMillis(120));
		this.metrics.recordTurn("safety_blocked", Duration.ofMillis(80));

		assertThat(this.registry.get("play.turn.outcome").tag("status", "generated").counter().count())
				.isEqualTo(1);
		assertThat(this.registry.get("play.turn.outcome").tag("status", "safety_blocked").counter()
				.count()).isEqualTo(1);
	}

	/** 지연은 상태별로 나뉜다 — 차단된 턴은 대개 짧고, 함께 재면 p95 가 낮게 보인다. */
	@Test
	void B48_duration_is_recorded_per_status() {
		this.metrics.recordTurn("generated", Duration.ofMillis(200));

		assertThat(this.registry.get("play.turn.duration").tag("status", "generated").timer().count())
				.isEqualTo(1);
	}

	/** <b>세션 id 는 태그가 아니다</b> (I-3). 태그 이름 목록이 닫혀 있어야 한다. */
	@Test
	void I3_no_identifier_becomes_a_tag() {
		this.metrics.recordTurn("generated", Duration.ofMillis(10));

		assertThat(this.registry.get("play.turn.duration").timer().getId().getTags())
				.extracting(io.micrometer.core.instrument.Tag::getKey)
				.containsExactly("status");
	}
}

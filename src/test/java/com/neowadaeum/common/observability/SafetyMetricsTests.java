package com.neowadaeum.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.common.spi.SafetyCategory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * B-48 — <b>차단율은 나눗셈이 아니라 두 카운터다</b> (§12).
 *
 * <p>비율만 기록하면 분모를 잃는다 — 차단율 10% 가 10건 중 1건인지 10만 건 중 1만 건인지는
 * 다른 사건이다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class SafetyMetricsTests {

	private final MeterRegistry registry = new SimpleMeterRegistry();

	private final SafetyMetrics metrics = new SafetyMetrics(this.registry);

	/** 통과와 차단이 각각 남는다 — 대시보드가 비율을 만들 수 있다. */
	@Test
	void B48_passes_and_blocks_are_counted_separately() {
		this.metrics.record("l2", false, Set.of());
		this.metrics.record("l2", false, Set.of());
		this.metrics.record("l2", true, Set.of(SafetyCategory.REAL_PERSON_HARM));

		assertThat(count("safety.judgement", "level", "l2", "outcome", "passed")).isEqualTo(2);
		assertThat(count("safety.judgement", "level", "l2", "outcome", "blocked")).isEqualTo(1);
	}

	/** <b>단계를 나눈다.</b> 입력과 출력은 다른 사건이며 함께 세면 구분이 사라진다. */
	@Test
	void B48_l1_and_l2_are_not_mixed() {
		this.metrics.record("l1", true, Set.of(SafetyCategory.REAL_PERSON_HARM));
		this.metrics.record("l2", true, Set.of(SafetyCategory.REAL_PERSON_HARM));

		assertThat(count("safety.judgement", "level", "l1", "outcome", "blocked")).isEqualTo(1);
		assertThat(count("safety.judgement", "level", "l2", "outcome", "blocked")).isEqualTo(1);
	}

	/** 분류별로도 센다 — 어느 분류가 늘었는지 모르면 오탐 급증에 대응할 수 없다. */
	@Test
	void B48_blocked_categories_are_counted() {
		this.metrics.record("l2", true,
				Set.of(SafetyCategory.REAL_PERSON_HARM, SafetyCategory.MINOR_SEXUAL));

		assertThat(this.registry.find("safety.blocked.category").counters()).hasSize(2);
	}

	/** 통과한 판정은 분류 카운터를 건드리지 않는다. */
	@Test
	void B48_a_passing_judgement_touches_no_category() {
		this.metrics.record("l2", false, Set.of());

		assertThat(this.registry.find("safety.blocked.category").counters()).isEmpty();
	}

	/** <b>태그 값은 미리 정해진 짧은 목록에서만 온다</b> (I-3, S-3). */
	@Test
	void I3_tags_carry_no_free_text() {
		this.metrics.record("l2", true, Set.of(SafetyCategory.REAL_PERSON_HARM));

		assertThat(this.registry.find("safety.blocked.category").counters())
				.allSatisfy(counter -> assertThat(counter.getId().getTags())
						.allSatisfy(tag -> assertThat(tag.getValue()).matches("[a-z0-9_]+")));
	}

	/** {@code null} 분류에도 넘어진다 — 판정기가 빈 집합 대신 null 을 줄 수 있다. */
	@Test
	void B48_a_null_category_set_is_tolerated() {
		this.metrics.record("l1", true, null);

		assertThat(count("safety.judgement", "level", "l1", "outcome", "blocked")).isEqualTo(1);
	}

	private double count(String name, String... tags) {
		var counter = this.registry.find(name).tags(tags).counter();
		return (counter != null) ? counter.count() : 0;
	}
}

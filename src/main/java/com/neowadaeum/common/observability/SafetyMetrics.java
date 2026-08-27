package com.neowadaeum.common.observability;

import com.neowadaeum.common.spi.SafetyCategory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 세이프티 판정을 센다 (§12 B-48).
 *
 * <p><b>차단율은 나눗셈이 아니라 두 카운터다.</b> 통과와 차단을 각각 세면 대시보드가 비율을
 * 만들 수 있고, 반대로 비율만 기록하면 <b>분모가 얼마였는지</b>를 잃는다 — 차단율 10% 가
 * 10건 중 1건인지 10만 건 중 1만 건인지는 다른 사건이다.
 *
 * <p><b>분류별로도 센다.</b> 어느 분류가 늘었는지 모르면 오탐 급증(B-64 의 시나리오)에 대응할
 * 수 없다. 분류는 <b>미리 정해진 열거형</b>이므로 카디널리티가 닫혀 있다.
 *
 * <p><b>실제 문자열은 어디에도 넣지 않는다</b> (S-3, S-11). 걸린 텍스트는 메트릭의 관심사가
 * 아니다.
 */
@Component
public class SafetyMetrics {

	private static final String JUDGEMENT = "safety.judgement";

	private static final String BLOCKED_CATEGORY = "safety.blocked.category";

	private final MeterRegistry registry;

	public SafetyMetrics(MeterRegistry registry) {
		this.registry = registry;
	}

	/**
	 * 판정 한 건.
	 *
	 * @param level {@code l1} 또는 {@code l2}. 입력과 출력은 다른 사건이며 함께 세면 구분이 사라진다
	 * @param blocked 통과하지 못했는가
	 * @param categories 걸린 분류. 통과면 비어 있다
	 */
	public void record(String level, boolean blocked, Set<SafetyCategory> categories) {
		Counter.builder(JUDGEMENT)
				.tag("level", level)
				.tag("outcome", blocked ? "blocked" : "passed")
				.register(this.registry)
				.increment();

		if (categories == null) {
			return;
		}
		for (SafetyCategory category : categories) {
			Counter.builder(BLOCKED_CATEGORY)
					.tag("level", level)
					.tag("category", category.name().toLowerCase(java.util.Locale.ROOT))
					.register(this.registry)
					.increment();
		}
	}
}

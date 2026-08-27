package com.neowadaeum.common.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * 턴이 얼마나 걸렸고 어떻게 끝났는지 (§12 B-48).
 *
 * <p><b>Provider 호출 시간과 나눠 본다.</b> 붙어 있으면 느린 원인이 모델인지 우리인지 알 수
 * 없다 — 그 구분이 스트리밍 도입 판단(B-46)의 근거가 된다.
 *
 * <p><b>세션 id 를 태그로 쓰지 않는다</b> (I-3). 세션마다 시계열이 하나씩 생기면 저장소가
 * 터지기도 하지만, 그전에 그것은 <b>누가 언제 무엇을 했는지의 기록</b>이다.
 */
@Component
public class TurnMetrics {

	private static final String DURATION = "play.turn.duration";

	private static final String OUTCOME = "play.turn.outcome";

	private final MeterRegistry registry;

	public TurnMetrics(MeterRegistry registry) {
		this.registry = registry;
	}

	/**
	 * 턴 하나가 끝났다.
	 *
	 * @param status {@code generated} · {@code ended} · {@code safety_blocked}. 파이프라인의 결과
	 *     그대로이며 <b>차단도 하나의 결과</b>다 — 실패로 세면 에러율과 섞인다
	 * @param elapsed 파이프라인 전체. Provider 호출과 검수와 저장을 모두 포함한다
	 */
	public void recordTurn(String status, Duration elapsed) {
		Timer.builder(DURATION)
				.tag("status", status)
				.publishPercentileHistogram()
				.register(this.registry)
				.record(elapsed);

		Counter.builder(OUTCOME).tag("status", status).register(this.registry).increment();
	}
}

package com.neowadaeum.ai.log;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AI 호출을 세면서 기록한다 (§12 B-48).
 *
 * <p><b>기록기를 감싼다.</b> 모든 호출이 이미 여기를 지나므로 새 계측 지점을 만들 이유가 없고,
 * 만들면 <b>둘 중 하나만 지나는 경로</b>가 생긴다.
 *
 * <p><b>{@code ai_call_log} 가 있는데 왜 세는가.</b> 그 표는 사후 조사용이다 — 실시간으로 보려면
 * 표를 스캔해야 하고, 그것은 I-20 이 도달률에 대해 금지한 것과 같은 성질의 실수다.
 *
 * <p><b>태그는 미리 정해진 짧은 목록에서만 온다</b> (S-3, I-3). {@code sessionId} 도 원문도
 * 들어가지 않는다.
 *
 * <p><b>계측 실패가 기록을 막지 않는다.</b> 세는 일이 남기는 일보다 중요할 수 없다.
 */
public class MeteredAiCallRecorder implements AiCallRecorder {

	private static final Logger log = LoggerFactory.getLogger(MeteredAiCallRecorder.class);

	private static final String CALLS = "ai.call";

	private static final String LATENCY = "ai.call.latency";

	private static final String TOKENS = "ai.call.tokens";

	/**
	 * <b>이름이 통화를 든다</b> (#311, §13-53). 예전 이름 {@code ai.call.cost.micro} 는 배율만
	 * 말했고, 그 카운터를 더하는 대시보드는 무엇을 더하는지 몰랐다.
	 */
	private static final String COST = "ai.call.cost.micro.krw";

	private static final String FALLBACK = "ai.call.fallback";

	private final AiCallRecorder delegate;

	private final MeterRegistry registry;

	public MeteredAiCallRecorder(AiCallRecorder delegate, MeterRegistry registry) {
		this.delegate = delegate;
		this.registry = registry;
	}

	@Override
	public void record(AiCallLog.Draft draft) {
		try {
			meter(draft);
		}
		catch (RuntimeException ex) {
			// 좌표까지만 남긴다 (S-3).
			log.warn("failed to meter an ai call: purpose={}", draft.purpose());
		}
		this.delegate.record(draft);
	}

	/**
	 * <b>응답 원문이 비어 있으면 실패한 호출이다.</b> 그 구분이 Provider 실패율의 정의이며,
	 * 여기서 정하지 않으면 대시보드마다 다르게 센다.
	 */
	private void meter(AiCallLog.Draft draft) {
		String outcome = (draft.responseRaw() != null) ? "success" : "failure";

		Counter.builder(CALLS)
				.tag("provider", draft.providerId())
				.tag("model", draft.modelId())
				.tag("purpose", draft.purpose())
				.tag("outcome", outcome)
				.register(this.registry)
				.increment();

		if (draft.fallbackFrom() != null) {
			// R3.7 — fallback 이 얼마나 자주 도는지가 곧 원래 provider 의 건강 상태다.
			Counter.builder(FALLBACK)
					.tag("from", draft.fallbackFrom())
					.tag("to", draft.providerId())
					.register(this.registry)
					.increment();
		}
		if (draft.latencyMs() != null) {
			Timer.builder(LATENCY)
					.tag("provider", draft.providerId())
					.tag("purpose", draft.purpose())
					.publishPercentileHistogram()
					.register(this.registry)
					.record(Duration.ofMillis(draft.latencyMs()));
		}
		countTokens(draft, "input", draft.inputTokens());
		countTokens(draft, "output", draft.outputTokens());

		// 단가 설정이 없으면 비용이 null 이고, 그러면 세지 않는다 — 0 을 더하면 "공짜로 돌았다" 가
		// 되고, 그 합계는 조용히 낮다 (#311).
		if (draft.costMicroKrw() != null) {
			Counter.builder(COST)
					.tag("provider", draft.providerId())
					.tag("model", draft.modelId())
					.register(this.registry)
					.increment(draft.costMicroKrw());
		}
	}

	private void countTokens(AiCallLog.Draft draft, String direction, Integer tokens) {
		if (tokens == null) {
			return;
		}
		Counter.builder(TOKENS)
				.tag("provider", draft.providerId())
				.tag("model", draft.modelId())
				.tag("purpose", draft.purpose())
				.tag("direction", direction.toLowerCase(Locale.ROOT))
				.register(this.registry)
				.increment(tokens);
	}
}

package com.neowadaeum.ai.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.BDDMockito.willThrow;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * B-48 — AI 호출을 세면서 기록한다 (§12).
 *
 * <p><b>기록기를 감싸는 것이 설계다.</b> 모든 호출이 이미 여기를 지나므로 새 계측 지점을 만들
 * 이유가 없고, 만들면 둘 중 하나만 지나는 경로가 생긴다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class MeteredAiCallRecorderTests {

	private final MeterRegistry registry = new SimpleMeterRegistry();

	private final AiCallRecorder delegate = mock(AiCallRecorder.class);

	private final MeteredAiCallRecorder recorder = new MeteredAiCallRecorder(this.delegate,
			this.registry);

	/** 세고 나서 <b>넘긴다.</b> 계측이 기록을 대체하지 않는다. */
	@Test
	void B48_the_call_is_still_recorded() {
		this.recorder.record(draft("응답 원문", null));

		verify(this.delegate).record(any());
	}

	/** <b>응답 원문이 비어 있으면 실패한 호출이다.</b> 그 구분이 Provider 실패율의 정의다. */
	@Test
	void B48_a_missing_response_counts_as_a_failure() {
		this.recorder.record(draft("응답 원문", null));
		this.recorder.record(draft(null, null));

		assertThat(count("ai.call", "outcome", "success")).isEqualTo(1);
		assertThat(count("ai.call", "outcome", "failure")).isEqualTo(1);
	}

	/** fallback 발동은 따로 센다 — 그것이 곧 원래 provider 의 건강 상태다 (R3.7). */
	@Test
	void R3_7_a_fallback_is_counted_with_both_ends() {
		this.recorder.record(draft("응답 원문", "anthropic"));

		assertThat(count("ai.call.fallback", "from", "anthropic", "to", "fixed")).isEqualTo(1);
	}

	/** fallback 이 없으면 그 카운터는 만들어지지도 않는다. */
	@Test
	void R3_7_no_fallback_means_no_counter() {
		this.recorder.record(draft("응답 원문", null));

		assertThat(this.registry.find("ai.call.fallback").counters()).isEmpty();
	}

	/** 토큰은 방향별로 센다 — 입력과 출력은 단가가 다르다. */
	@Test
	void B48_tokens_are_counted_by_direction() {
		this.recorder.record(draft("응답 원문", null));

		assertThat(count("ai.call.tokens", "direction", "input")).isEqualTo(11);
		assertThat(count("ai.call.tokens", "direction", "output")).isEqualTo(22);
	}

	/** <b>계측 실패가 기록을 막지 않는다.</b> 세는 일이 남기는 일보다 중요할 수 없다. */
	@Test
	void B48_a_metering_failure_does_not_stop_the_record() {
		MeterRegistry broken = new SimpleMeterRegistry() {
			@Override
			protected io.micrometer.core.instrument.Counter newCounter(
					io.micrometer.core.instrument.Meter.Id id) {
				throw new IllegalStateException("계측 실패");
			}
		};
		AiCallRecorder metered = new MeteredAiCallRecorder(this.delegate, broken);

		metered.record(draft("응답 원문", null));

		verify(this.delegate).record(any());
	}

	/** 위임이 터지면 그것은 그대로 올라간다 — 계측이 기록의 실패를 숨기지 않는다. */
	@Test
	void B48_a_delegate_failure_is_not_swallowed() {
		willThrow(new IllegalStateException("기록 실패")).given(this.delegate).record(any());

		org.assertj.core.api.Assertions
				.assertThatThrownBy(() -> this.recorder.record(draft("응답 원문", null)))
				.isInstanceOf(IllegalStateException.class);
	}

	/** <b>세션 id 도 원문도 태그가 아니다</b> (I-3, S-3). */
	@Test
	void I3_no_identifier_or_raw_text_becomes_a_tag() {
		AiCallLog.Draft draft = draft("응답 원문", null);

		this.recorder.record(draft);

		assertThat(this.registry.find("ai.call").counters())
				.allSatisfy(counter -> assertThat(counter.getId().getTags())
						.extracting(io.micrometer.core.instrument.Tag::getKey)
						.containsExactlyInAnyOrder("provider", "model", "purpose", "outcome"));
	}

	private AiCallLog.Draft draft(String responseRaw, String fallbackFrom) {
		return new AiCallLog.Draft(UUID.randomUUID(), null, "turn", "fixed", "scenario", fallbackFrom,
				"요청 원문", responseRaw, 11, 22, 33, 44L, "[]", 1);
	}

	private double count(String name, String... tags) {
		var counter = this.registry.find(name).tags(tags).counter();
		return (counter != null) ? counter.count() : 0;
	}
}

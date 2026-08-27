package com.neowadaeum.play.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.common.support.FixedTokenCounter;
import com.neowadaeum.common.support.RecentTurnsProperties;
import com.neowadaeum.common.support.SummaryBudget;
import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.domain.SafetyVerdict;
import com.neowadaeum.play.domain.StorySummary;
import com.neowadaeum.play.domain.Turn;
import com.neowadaeum.play.port.SummarizationPort;
import com.neowadaeum.play.port.SummaryRequest;
import com.neowadaeum.play.repository.PlaySessionRepository;
import com.neowadaeum.play.repository.StorySummaryRepository;
import com.neowadaeum.play.repository.TurnRepository;
import com.neowadaeum.play.port.ProviderCallFailedException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 요약 파이프라인 (R4.5, R4.6, B-34).
 *
 * <p>턴과 요약이 실제로 저장되는 경로라 DB 에 대고 본다 (ADR-0001 의 container 분류). Provider 는
 * 붙이지 않는다 — 무엇을 넘겨받는지까지가 이 테스트의 관심사이며, 벤더 호출은 어댑터 계약 테스트가 본다.
 */
class StorySummarizerIntegrationTests extends ContainerTestBase {

	private static final Instant NOW = Instant.parse("2026-08-26T04:05:06Z");

	private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

	@Autowired
	private PlaySessionRepository sessions;

	@Autowired
	private TurnRepository turns;

	@Autowired
	private StorySummaryRepository summaries;

	@Autowired
	private PlatformTransactionManager playTransactionManager;

	/** 요청을 받아 적고 정해진 답을 돌려주는 요약기. */
	private static final class RecordingPort implements SummarizationPort {

		private final List<SummaryRequest> received = new ArrayList<>();

		private final List<String> answers;

		private final AtomicInteger calls = new AtomicInteger();

		private RecordingPort(String... answers) {
			this.answers = List.of(answers);
		}

		@Override
		public String summarize(SummaryRequest request) {
			this.received.add(request);
			int index = this.calls.getAndIncrement();
			return this.answers.get(Math.min(index, this.answers.size() - 1));
		}
	}

	private StorySummarizer summarizerWith(SummarizationPort port) {
		return new StorySummarizer(this.turns, this.summaries, port, new FixedTokenCounter(),
				RecentTurnsProperties.defaults(), this.playTransactionManager, FIXED);
	}

	private UUID sessionWithTurns(int count) {
		UUID sessionId = this.sessions.save(PlaySession.start(UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID(), "fixed", "scenario-v1", false, NOW)).getId();

		for (int turnNo = 1; turnNo <= count; turnNo++) {
			this.turns.save(Turn.create(new Turn.TurnDraft(sessionId, turnNo, 1,
					"[{\"type\":\"NARRATION\",\"speakerName\":null,\"text\":\"%d턴의 본문\"}]".formatted(turnNo),
					"[{\"choiceId\":\"c%d\",\"order\":1,\"text\":\"%d턴의 선택지\"}]".formatted(turnNo, turnNo),
					null, false, false, null, SafetyVerdict.PASS, true, false), NOW));
		}
		return sessionId;
	}

	/** 완충 구간 안이면 Provider 를 부르지 않는다 — 부르면 그만큼이 비용이다 (§13-2). */
	@Test
	void R4_5_a_session_inside_the_buffer_does_not_call_the_summarizer() {
		RecordingPort port = new RecordingPort("압축된 줄거리");

		summarizerWith(port).compress(sessionWithTurns(8), 8);

		assertThat(port.calls).hasValue(0);
	}

	/**
	 * <b>완충 구간을 넘긴 분량이 요약으로 옮겨진다</b> (R4.5).
	 *
	 * <p>넘어가는 것은 <b>턴 요지와 고른 선택지</b>다 (R4.7) — 무엇을 골랐는가가 이야기의 분기다.
	 */
	@Test
	void R4_5_turns_past_the_buffer_are_merged_into_the_summary() {
		RecordingPort port = new RecordingPort("압축된 줄거리");
		UUID sessionId = sessionWithTurns(10);

		summarizerWith(port).compress(sessionId, 10);

		assertThat(port.calls).hasValue(1);
		SummaryRequest sent = port.received.getFirst();
		assertThat(sent.previousSummary()).as("첫 압축에는 직전 요약이 없다").isNull();
		assertThat(sent.turns()).extracting(SummaryRequest.TurnDigest::turnNo).containsExactly(1, 2);
		assertThat(sent.maxTokens()).isEqualTo(SummaryBudget.MAX_TOKENS);

		StorySummary stored = this.summaries
				.findFirstBySessionIdAndDeletedAtIsNullOrderByUptoTurnNoDescCreatedAtDesc(sessionId).orElseThrow();
		assertThat(stored.getSummaryText()).isEqualTo("압축된 줄거리");
		assertThat(stored.getUptoTurnNo()).isEqualTo(2);
	}

	/** 두 번째 압축은 <b>직전 요약을 이어 쓴다</b> — 새 턴만 주면 앞의 이야기를 모른 채 요약한다. */
	@Test
	void R4_5_the_next_compression_continues_from_the_previous_summary() {
		RecordingPort port = new RecordingPort("첫 요약", "이어진 요약");
		UUID sessionId = sessionWithTurns(12);
		StorySummarizer summarizer = summarizerWith(port);

		summarizer.compress(sessionId, 10);
		summarizer.compress(sessionId, 12);

		assertThat(port.calls).hasValue(2);
		SummaryRequest second = port.received.get(1);
		assertThat(second.previousSummary()).isEqualTo("첫 요약");
		assertThat(second.turns()).extracting(SummaryRequest.TurnDigest::turnNo).containsExactly(3, 4);
	}

	/** 같은 구간을 두 번 부르지 않는다 — 결과는 같고 비용만 두 배다. */
	@Test
	void R4_5_an_already_summarized_window_is_not_requested_again() {
		RecordingPort port = new RecordingPort("압축된 줄거리");
		UUID sessionId = sessionWithTurns(10);
		StorySummarizer summarizer = summarizerWith(port);

		summarizer.compress(sessionId, 10);
		summarizer.compress(sessionId, 10);

		assertThat(port.calls).hasValue(1);
	}

	/**
	 * <b>R4.5 — 예산을 넘으면 한 번 더 압축한다.</b>
	 *
	 * <p>재압축의 입력은 방금 만든 요약이다. 원문 턴은 DB 에 그대로 남으므로(R4.8) 손실이 아니라
	 * 표현을 줄이는 일이다.
	 */
	@Test
	void R4_5_an_oversized_summary_is_compressed_once_more() {
		String tooLong = "가".repeat(4000);
		RecordingPort port = new RecordingPort(tooLong, "짧아진 요약");
		UUID sessionId = sessionWithTurns(10);

		summarizerWith(port).compress(sessionId, 10);

		assertThat(port.calls).as("초과분에 대해 재압축이 한 번 더 일어난다").hasValue(2);
		assertThat(port.received.get(1).previousSummary()).isEqualTo(tooLong);
		assertThat(port.received.get(1).turns()).as("재압축의 입력은 요약 자체다").isEmpty();

		StorySummary stored = this.summaries
				.findFirstBySessionIdAndDeletedAtIsNullOrderByUptoTurnNoDescCreatedAtDesc(sessionId).orElseThrow();
		assertThat(stored.getSummaryText()).isEqualTo("짧아진 요약");
	}

	/**
	 * <b>요약 실패는 턴을 되돌리지 않는다</b> (R4.6).
	 *
	 * <p>이 코드가 도는 시점에 턴은 이미 응답됐다. 실패하면 요약이 없는 채로 남고, 다음 턴이 같은
	 * 구간을 다시 시도한다.
	 */
	@Test
	void R4_6_a_failed_summary_leaves_the_session_without_one() {
		UUID sessionId = sessionWithTurns(10);

		summarizerWith(request -> {
			throw new ProviderCallFailedException("vendor down");
		}).compress(sessionId, 10);

		assertThat(this.summaries
				.findFirstBySessionIdAndDeletedAtIsNullOrderByUptoTurnNoDescCreatedAtDesc(sessionId)).isEmpty();
	}
}

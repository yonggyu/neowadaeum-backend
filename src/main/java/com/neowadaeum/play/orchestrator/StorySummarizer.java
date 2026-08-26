package com.neowadaeum.play.orchestrator;

import com.neowadaeum.common.support.SummaryBudget;
import com.neowadaeum.common.support.TokenCounter;
import com.neowadaeum.common.support.RecentTurnsProperties;
import com.neowadaeum.play.domain.StorySummary;
import com.neowadaeum.play.domain.Turn;
import com.neowadaeum.play.port.SummarizationPort;
import com.neowadaeum.play.port.SummaryRequest;
import com.neowadaeum.play.repository.StorySummaryRepository;
import com.neowadaeum.play.repository.TurnRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 오래된 턴을 요약으로 옮긴다 (§4.2, R4.5, B-34).
 *
 * <p><b>무엇을 옮기는가.</b> {@code summaryMerge}(기본 8)보다 오래된 턴이 대상이다 (§13-2). 그
 * 경계보다 최근의 턴은 프롬프트에 원문·압축본으로 실리므로(R4.7) 요약이 중복해서 담을 이유가 없다.
 *
 * <p><b>이어 쓴다.</b> 직전 요약을 함께 넘겨 그 뒤를 잇게 한다 — 새 턴만 주면 모델은 앞의 이야기를
 * 모른 채 요약하고, 그 요약이 다음 턴들의 전제가 된다.
 *
 * <p><b>예산을 넘으면 한 번 더 압축한다</b> (R4.5). 재압축의 입력은 <b>방금 만들어진 요약</b>이다 —
 * 원문 턴은 DB 에 그대로 남으므로(R4.8) 이것은 손실이 아니라 <b>표현을 줄이는 일</b>이다.
 * 한 번으로 끝낸다: 줄지 않는 모델에게 계속 물으면 비용만 늘고, 다음 턴에 다시 기회가 온다.
 *
 * <p><b>트랜잭션 안에서 Provider 를 부르지 않는다.</b> 읽기 → (트랜잭션 밖) 호출 → 쓰기 순서이며,
 * 턴 파이프라인과 같은 규칙이다.
 *
 * <p><b>실패는 조용히 넘긴다.</b> 이 코드가 도는 시점에 <b>턴은 이미 응답된 뒤</b>다 (R4.6). 여기서
 * 예외를 올려도 되돌릴 것이 없고, 실패가 쌓이면 프롬프트가 얇아질 뿐 <b>잘못된 이야기가 나가지는
 * 않는다.</b> 다음 턴이 같은 구간을 다시 시도한다.
 */
@Service
public class StorySummarizer {

	private static final Logger log = LoggerFactory.getLogger(StorySummarizer.class);

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final TurnRepository turns;

	private final StorySummaryRepository summaries;

	private final SummarizationPort summarizer;

	private final TokenCounter tokens;

	private final RecentTurnsProperties recentTurns;

	private final TransactionTemplate transactions;

	private final Clock clock;

	public StorySummarizer(TurnRepository turns, StorySummaryRepository summaries, SummarizationPort summarizer,
			TokenCounter tokens, RecentTurnsProperties recentTurns,
			PlatformTransactionManager playTransactionManager, Clock clock) {
		this.turns = turns;
		this.summaries = summaries;
		this.summarizer = summarizer;
		this.tokens = tokens;
		this.recentTurns = recentTurns;
		this.transactions = new TransactionTemplate(playTransactionManager);
		this.clock = clock;
	}

	/**
	 * 이 세션의 요약을 최신 상태로 만든다. 옮길 턴이 없으면 아무 일도 하지 않는다.
	 *
	 * @param sessionId     대상 세션
	 * @param currentTurnNo 방금 저장된 턴 번호
	 */
	public void compress(UUID sessionId, int currentTurnNo) {
		Optional<StorySummary> current = this.transactions.execute(status -> this.summaries
				.findFirstBySessionIdAndDeletedAtIsNullOrderByUptoTurnNoDescCreatedAtDesc(sessionId));

		Optional<SummaryWindow> window = SummaryWindow.of(currentTurnNo,
				current.map(StorySummary::getUptoTurnNo).orElse(0), this.recentTurns.summaryMerge());
		if (window.isEmpty()) {
			return;
		}
		int from = window.get().from();
		int mergeUpto = window.get().to();

		List<Turn> pending = this.transactions.execute(status -> this.turns
				.findBySessionIdAndDeletedAtIsNullAndTurnNoBetweenOrderByTurnNoAsc(sessionId, from, mergeUpto));
		if (pending == null || pending.isEmpty()) {
			return;
		}

		String previous = current.map(StorySummary::getSummaryText).orElse(null);
		String compressed = compress(previous, pending);
		if (compressed == null) {
			return;
		}

		int estimate = this.tokens.count(compressed);
		this.transactions.executeWithoutResult(status -> this.summaries
				.save(StorySummary.of(sessionId, mergeUpto, compressed, estimate, Instant.now(this.clock))));
	}

	/**
	 * 압축과 재압축. 실패하면 {@code null} 이다 — 호출자는 그것을 "이번엔 못 했다"로 읽는다.
	 */
	private String compress(String previous, List<Turn> pending) {
		List<SummaryRequest.TurnDigest> digests = pending.stream().map(StorySummarizer::toDigest).toList();

		String compressed;
		try {
			compressed = this.summarizer.summarize(new SummaryRequest(previous, digests, SummaryBudget.MAX_TOKENS));
		}
		catch (RuntimeException ex) {
			// 원문도 응답 원문도 남기지 않는다 (S-3). 남기는 것은 "이번 턴에는 못 옮겼다"까지다.
			log.warn("summary compression failed; the next turn will try the same window again (R4.6)");
			return null;
		}

		if (this.tokens.count(compressed) <= SummaryBudget.MAX_TOKENS) {
			return compressed;
		}

		try {
			// R4.5 — 예산을 넘으면 한 번 더. 입력은 방금 만든 요약이다.
			String recompressed = this.summarizer.summarize(
					new SummaryRequest(compressed, List.of(), SummaryBudget.MAX_TOKENS));
			if (this.tokens.count(recompressed) > SummaryBudget.MAX_TOKENS) {
				// 저장은 한다. 조립기가 예산 안에서 무엇을 실을지 판단하며(§4.4), 다음 턴에
				// 다시 줄어들 기회가 있다. 여기서 버리면 그 구간이 영영 요약되지 않는다.
				log.warn("recompressed summary still exceeds the budget; storing it anyway (R4.5)");
			}
			return recompressed;
		}
		catch (RuntimeException ex) {
			log.warn("summary recompression failed; storing the first compression (R4.5)");
			return compressed;
		}
	}

	/**
	 * 저장된 턴 하나를 요약 재료로 바꾼다 (R4.7).
	 *
	 * <p><b>고른 선택지 본문을 함께 넘긴다.</b> 무엇을 골랐는가가 이야기의 분기이며, 빠지면 요약은
	 * "무슨 일이 있었나"만 남기고 "왜 그렇게 됐나"를 잃는다.
	 */
	private static SummaryRequest.TurnDigest toDigest(Turn turn) {
		StringBuilder body = new StringBuilder();
		for (JsonNode paragraph : JSON.readTree(turn.getParagraphs())) {
			body.append(body.isEmpty() ? "" : "\n").append(paragraph.path("text").asString(""));
		}

		return new SummaryRequest.TurnDigest(turn.getTurnNo(), chosenText(turn), body.toString());
	}

	private static String chosenText(Turn turn) {
		if (turn.getChosenChoiceId() == null) {
			return null;
		}
		for (JsonNode choice : JSON.readTree(turn.getChoices())) {
			if (turn.getChosenChoiceId().equals(choice.path("choiceId").asString(null))) {
				return choice.path("text").asString(null);
			}
		}
		return null;
	}
}

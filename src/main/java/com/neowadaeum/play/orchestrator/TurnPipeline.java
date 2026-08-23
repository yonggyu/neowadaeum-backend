package com.neowadaeum.play.orchestrator;

import com.neowadaeum.ai.provider.StoryProvider;
import com.neowadaeum.ai.provider.TurnRequest;
import com.neowadaeum.ai.provider.TurnResult;
import com.neowadaeum.catalog.query.StoryVersionFacade;
import com.neowadaeum.catalog.query.StoryVersionView;
import com.neowadaeum.play.domain.GameStateSnapshot;
import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.domain.SafetyVerdict;
import com.neowadaeum.play.domain.Turn;
import com.neowadaeum.play.engine.ChapterDefinition;
import com.neowadaeum.play.engine.ChapterEngine;
import com.neowadaeum.play.engine.EndingDefinition;
import com.neowadaeum.play.engine.EndingEngine;
import com.neowadaeum.play.engine.GameState;
import com.neowadaeum.play.engine.GameStateEngine;
import com.neowadaeum.play.engine.StateChanges;
import com.neowadaeum.play.engine.StateSchema;
import com.neowadaeum.play.repository.GameStateSnapshotRepository;
import com.neowadaeum.play.repository.PlaySessionRepository;
import com.neowadaeum.play.repository.TurnRepository;
import com.neowadaeum.safety.l2.RuleBasedSafetyJudge;
import com.neowadaeum.safety.l2.SafetyJudgement;
import com.neowadaeum.safety.l2.SafetyOutcome;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 턴 처리 파이프라인 (§4.3 의 3~11 단계, B-32 / S-9-1).
 *
 * <p>S-1 ~ S-8 이 만든 조각들이 여기서 하나가 된다. HTTP 진입점과 요청 검증은 S-9-2 다.
 *
 * <h2>트랜잭션 경계 — 이 클래스의 형태를 결정한 규칙</h2>
 *
 * <p><b>Provider 호출을 트랜잭션 안에서 하지 않는다</b> (§9.2, §13-14-a). 25초짜리 트랜잭션은
 * 커넥션 풀을 고갈시킨다. 그래서 <b>짧은 TX → 외부 호출 → 짧은 TX</b> 로 나누고, 그 경계를
 * {@code @Transactional} 이 아니라 {@link TransactionTemplate} 으로 <b>코드에 드러나게</b> 했다 —
 * 애노테이션은 어디서 시작하고 끝나는지가 보이지 않는다.
 *
 * <h2>순서</h2>
 *
 * <p>§4.3 의 8 → 9 → 10 → 11 순서를 지킨다. 상태를 먼저 병합해야 챕터·엔딩 판정이 <b>이번 턴의
 * 결과</b>를 보고 판단한다. 순서를 바꾸면 한 턴 늦게 반응한다.
 *
 * <h2>AI 제안값</h2>
 *
 * <p><b>{@code chapterAdvanceSuggested} · {@code endingSuggested} 를 판정에 넘기지 않는다</b>
 * (R7.1, R7.9, I-10). 엔진에 받을 파라미터가 없기도 하지만, 여기서도 <b>읽지 않는다</b> — 파이프라인이
 * 그 값을 다른 경로로 반영하면 엔진 시그니처만으로는 막히지 않는다.
 */
@Service
public class TurnPipeline {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final PlaySessionRepository sessions;
	private final TurnRepository turns;
	private final GameStateSnapshotRepository snapshots;
	private final StoryVersionFacade storyVersions;
	private final StoryProvider provider;
	private final RuleBasedSafetyJudge safetyJudge;
	private final GameStateEngine gameStateEngine;
	private final ChapterEngine chapterEngine;
	private final EndingEngine endingEngine;
	private final TransactionTemplate transactions;
	private final Clock clock;

	public TurnPipeline(PlaySessionRepository sessions, TurnRepository turns,
			GameStateSnapshotRepository snapshots, StoryVersionFacade storyVersions, StoryProvider provider,
			RuleBasedSafetyJudge safetyJudge, GameStateEngine gameStateEngine, ChapterEngine chapterEngine,
			EndingEngine endingEngine, PlatformTransactionManager playTransactionManager, Clock clock) {
		this.sessions = sessions;
		this.turns = turns;
		this.snapshots = snapshots;
		this.storyVersions = storyVersions;
		this.provider = provider;
		this.safetyJudge = safetyJudge;
		this.gameStateEngine = gameStateEngine;
		this.chapterEngine = chapterEngine;
		this.endingEngine = endingEngine;
		this.transactions = new TransactionTemplate(playTransactionManager);
		this.clock = clock;
	}

	/**
	 * 다음 턴을 만든다.
	 *
	 * @param sessionId         대상 세션
	 * @param chosenChoiceOrder 직전 턴에서 고른 선택지 순서. <b>세션의 첫 턴이면 {@code null}</b>
	 */
	public TurnOutcome advance(UUID sessionId, Integer chosenChoiceOrder) {
		PipelineContext context = this.transactions.execute(status -> readContext(sessionId));

		// ── 트랜잭션 밖 ── Provider 호출과 L2 검수 (§9.2, §13-14-a)
		Generated generated = generateAndScreen(context, chosenChoiceOrder);
		if (generated == null) {
			// I-2 — 통과하지 못한 본문은 여기서 끝난다. 저장도 반환도 하지 않는다.
			return blocked(context);
		}

		return this.transactions.execute(status -> commit(context, generated));
	}

	// ── 1) 짧은 TX — 읽기 ────────────────────────────────────

	private PipelineContext readContext(UUID sessionId) {
		PlaySession session = this.sessions.findById(sessionId)
				.orElseThrow(() -> new IllegalArgumentException("unknown session: " + sessionId));

		StoryVersionView version = this.storyVersions.findByVersionId(session.getStoryVersionId())
				.orElseThrow(() -> new IllegalStateException(
						"session points at a story version that no longer exists: " + session.getStoryVersionId()));

		GameState state = this.snapshots
				.findFirstBySessionIdAndDeletedAtIsNullOrderByTurnNoDesc(sessionId)
				.map(snapshot -> GameState.from(JSON.readTree(snapshot.getState())))
				.orElseGet(GameState::initial);

		int turnsInChapter = this.turns
				.countBySessionIdAndChapterNoAndDeletedAtIsNull(sessionId, session.getChapterNo());

		return new PipelineContext(session.getId(), session.getStoryVersionId(), session.getTurnNo(),
				session.getChapterNo(), turnsInChapter, state, version);
	}

	// ── 2) 트랜잭션 밖 — 생성과 검수 ─────────────────────────

	/**
	 * Provider 를 부르고 L2 를 통과시킨다 (§4.3 의 4~7 단계).
	 *
	 * <p><b>§9.2 — 즉시차단은 재생성 없이 차단한다.</b> 그 외 blocked 는 재생성 1회 후에도 걸리면 차단.
	 *
	 * <p>결정론 Provider(S-3)에서는 재생성이 같은 응답을 낸다. 그래서 재생성이 결과를 바꾸지
	 * 못하지만 <b>경로는 실제로 존재해야 한다</b> — 실 Provider(B-22)가 붙는 순간 의미가 생기고,
	 * 그때 이 자리를 새로 만들면 즉시차단과 재생성의 구분이 뒤늦게 들어온다.
	 *
	 * @return 통과한 결과. 차단이면 {@code null}
	 */
	private Generated generateAndScreen(PipelineContext context, Integer chosenChoiceOrder) {
		TurnRequest request = new TurnRequest(context.storyVersionId(), context.turnNo(), chosenChoiceOrder);

		TurnResult result = this.provider.generateTurn(request);
		SafetyJudgement judgement = screen(result);

		if (judgement.outcome() == SafetyOutcome.PASS) {
			return new Generated(result, SafetyVerdict.PASS, judgement);
		}
		if (judgement.blocked()) {
			return null;
		}

		TurnResult regenerated = this.provider.generateTurn(request);
		SafetyJudgement second = screen(regenerated);
		if (second.outcome() != SafetyOutcome.PASS) {
			return null;
		}
		return new Generated(regenerated, SafetyVerdict.REVISED, second);
	}

	/** L2 대상은 본문과 선택지 둘 다다 (§9.1). */
	private SafetyJudgement screen(TurnResult result) {
		List<String> choiceTexts = result.choices().stream().map(TurnResult.ProposedChoice::text).toList();
		return this.safetyJudge.judge(List.of(result.narrative()), choiceTexts);
	}

	// ── 3) 짧은 TX — 판정과 저장 ─────────────────────────────

	private TurnOutcome commit(PipelineContext context, Generated generated) {
		Instant now = Instant.now(this.clock);
		int newTurnNo = context.turnNo() + 1;

		StateSchema schema = StateSchema.from(context.version().stateSchema());
		StateChanges changes = StateChanges.from(generated.result().proposedStateChanges());

		// 8 — 화이트리스트 → clamp → 병합. 그다음 서버 전용 경로로 턴 번호를 올린다 (I-9).
		GameState merged = this.gameStateEngine.apply(context.state(), schema, changes)
				.advanceTo(context.chapterNo(), newTurnNo);

		// 9 — Chapter 판정 (서버 단독)
		List<ChapterDefinition> chapters = toChapterDefinitions(context.version());
		var chapterDecision = this.chapterEngine.decide(chapters, context.chapterNo(),
				context.turnsInChapter() + 1, merged);

		GameState afterChapter = chapterDecision.changed()
				? merged.advanceTo(chapterDecision.chapterNo(), newTurnNo) : merged;

		// 10 — Ending 판정 (서버 단독). 폴백 시점은 R7.7 그대로다.
		List<EndingDefinition> endings = toEndingDefinitions(context.version());
		var endingDecision = this.endingEngine.decide(endings, afterChapter,
				lastChapterExhausted(chapters, chapterDecision.chapterNo(),
						chapterDecision.changed() ? 1 : context.turnsInChapter() + 1));

		// 11 — 저장
		return persist(context, generated, afterChapter, chapterDecision, endingDecision, newTurnNo, now);
	}

	private TurnOutcome persist(PipelineContext context, Generated generated, GameState state,
			ChapterEngine.ChapterDecision chapterDecision, EndingEngine.EndingDecision endingDecision,
			int newTurnNo, Instant now) {

		boolean ended = endingDecision.reached();
		UUID endingId = ended ? endingKey(context, endingDecision) : null;

		String choicesJson = ended
				? "[]"
				: issuedChoices(context.sessionId(), newTurnNo, generated.result());

		Turn turn = this.turns.save(Turn.create(new Turn.TurnDraft(
				context.sessionId(), newTurnNo, chapterDecision.chapterNo(),
				JSON.writeValueAsString(List.of(generated.result().narrative())),
				choicesJson, null, chapterDecision.changed(), ended, endingId,
				generated.verdict()), now));

		// I-5 — append. 덮어쓰지 않는다.
		this.snapshots.save(GameStateSnapshot.capture(context.sessionId(), newTurnNo,
				state.toJson().toString(), now));

		PlaySession session = this.sessions.findById(context.sessionId()).orElseThrow();
		session.recordTurn(newTurnNo, chapterDecision.chapterNo(), now);
		if (ended) {
			session.complete(endingId, now);
		}

		return new TurnOutcome(
				ended ? TurnOutcome.TurnStatus.ENDED : TurnOutcome.TurnStatus.GENERATED,
				turn.getId(), newTurnNo, chapterDecision.changed(), chapterDecision.chapterNo(),
				endingId, endingDecision.endingIndex(), endingDecision.totalEndings(), java.util.Set.of());
	}

	private TurnOutcome blocked(PipelineContext context) {
		// R6.6 — 상태가 변하지 않는다. 세션의 턴 번호도 그대로다.
		return new TurnOutcome(TurnOutcome.TurnStatus.SAFETY_BLOCKED, null, context.turnNo(),
				false, context.chapterNo(), null, null, 0, java.util.Set.of());
	}

	// ── 보조 ────────────────────────────────────────────────

	/**
	 * I-1 — 선택지 식별자를 서버가 발급해 저장한다.
	 *
	 * <p>다음 요청은 이 값과 대조된다. AI 가 준 것은 {@code order} 와 {@code text} 뿐이다 (§13-3).
	 * {@code disabled} 는 P0 채택안대로 항상 {@code false} 다 (I-11, §13-3).
	 */
	private static String issuedChoices(UUID sessionId, int turnNo, TurnResult result) {
		var array = JSON.createArrayNode();
		result.choices().forEach(choice -> {
			var node = array.addObject();
			node.put("choiceId", ChoiceIdIssuer.issue(sessionId, turnNo, choice.order(), choice.text()));
			node.put("order", choice.order());
			node.put("text", choice.text());
			node.put("disabled", false);
			node.putNull("disabledReason");
		});
		return array.toString();
	}

	/**
	 * 엔딩 식별자.
	 *
	 * <p>catalog 의 {@code ending_def.id} 를 파사드가 싣지 않으므로 <b>버전과 엔딩 번호로 지목한다.</b>
	 * §13-1 상 엔딩은 버전에 묶여 있고, {@code ending_stat} 집계도 {@code (story_id, ending_no)} 기준이다.
	 * 여기서 UUID 를 만들지 않고 실제 id 가 필요해지는 시점은 B-08 복귀 때다.
	 */
	private static UUID endingKey(PipelineContext context, EndingEngine.EndingDecision decision) {
		return UUID.nameUUIDFromBytes(
				("%s|%d".formatted(context.storyVersionId(), decision.ending().endingNo()))
						.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	private static boolean lastChapterExhausted(List<ChapterDefinition> chapters, int chapterNo, int turnsInChapter) {
		int lastChapterNo = chapters.stream().mapToInt(ChapterDefinition::chapterNo).max().orElseThrow();
		if (chapterNo != lastChapterNo) {
			return false;
		}
		return chapters.stream()
				.filter(chapter -> chapter.chapterNo() == lastChapterNo)
				.anyMatch(chapter -> turnsInChapter >= chapter.maxTurns());
	}

	private static List<ChapterDefinition> toChapterDefinitions(StoryVersionView version) {
		return version.chapters().stream()
				.map(chapter -> new ChapterDefinition(chapter.chapterNo(), chapter.title(),
						chapter.entryCondition(), chapter.minTurns(), chapter.maxTurns()))
				.toList();
	}

	private static List<EndingDefinition> toEndingDefinitions(StoryVersionView version) {
		return version.endings().stream()
				.map(ending -> new EndingDefinition(ending.endingNo(), ending.label(), ending.condition(),
						ending.secret(), ending.defaultEnding()))
				.toList();
	}

	/** 읽기 트랜잭션이 모아 온 값. 트랜잭션 밖에서도 안전하도록 엔티티를 들고 나오지 않는다. */
	private record PipelineContext(UUID sessionId, UUID storyVersionId, int turnNo, int chapterNo,
			int turnsInChapter, GameState state, StoryVersionView version) {
	}

	/** L2 를 통과한 생성 결과. 통과하지 못한 것은 이 타입으로 존재하지 않는다 (I-2). */
	private record Generated(TurnResult result, SafetyVerdict verdict, SafetyJudgement judgement) {
	}
}

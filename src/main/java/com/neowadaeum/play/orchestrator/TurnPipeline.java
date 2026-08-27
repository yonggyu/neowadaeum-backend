package com.neowadaeum.play.orchestrator;

import com.neowadaeum.common.support.RecentTurnsProperties;
import com.neowadaeum.common.support.TurnBudgetProperties;
import com.neowadaeum.common.support.TurnDeadline;
import com.neowadaeum.play.port.GeneratedChoice;
import com.neowadaeum.play.port.GenerationContext;
import com.neowadaeum.play.port.GeneratedParagraph;
import com.neowadaeum.play.port.GeneratedTurn;
import com.neowadaeum.play.port.TurnGenerationPort;
import com.neowadaeum.play.port.TurnRequest;
import com.neowadaeum.catalog.query.StoryVersionFacade;
import com.neowadaeum.catalog.query.StoryVersionView;
import com.neowadaeum.play.domain.GameStateSnapshot;
import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.domain.SafetyVerdict;
import com.neowadaeum.play.domain.Turn;
import org.springframework.data.domain.Limit;
import tools.jackson.databind.JsonNode;
import com.neowadaeum.play.engine.ChapterDefinition;
import com.neowadaeum.play.engine.ChapterEngine;
import com.neowadaeum.play.engine.EndingDefinition;
import com.neowadaeum.play.engine.EndingEngine;
import com.neowadaeum.play.engine.GameState;
import com.neowadaeum.play.engine.GameStateEngine;
import com.neowadaeum.play.engine.StateChanges;
import com.neowadaeum.play.engine.StateSchema;
import com.neowadaeum.play.domain.StorySummary;
import com.neowadaeum.play.repository.GameStateSnapshotRepository;
import com.neowadaeum.play.repository.StorySummaryRepository;
import com.neowadaeum.play.repository.PlaySessionRepository;
import com.neowadaeum.play.repository.TurnRepository;
import com.neowadaeum.safety.l2.SafetyL2Judge;
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
	private final TurnGenerationPort provider;
	private final SafetyL2Judge safetyJudge;
	private final GameStateEngine gameStateEngine;
	private final ChapterEngine chapterEngine;
	private final EndingEngine endingEngine;
	private final TransactionTemplate transactions;
	private final RecentTurnsProperties recentTurns;

	private final StorySummaryRepository summaries;

	private final AsyncSummaryTrigger summaryTrigger;

	private final Clock clock;

	private final TurnBudgetProperties turnBudget;

	public TurnPipeline(PlaySessionRepository sessions, TurnRepository turns,
			GameStateSnapshotRepository snapshots, StoryVersionFacade storyVersions, TurnGenerationPort provider,
			SafetyL2Judge safetyJudge, GameStateEngine gameStateEngine, ChapterEngine chapterEngine,
			EndingEngine endingEngine, RecentTurnsProperties recentTurns, StorySummaryRepository summaries,
			AsyncSummaryTrigger summaryTrigger, PlatformTransactionManager playTransactionManager, Clock clock,
			TurnBudgetProperties turnBudget) {
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
		this.recentTurns = recentTurns;
		this.summaries = summaries;
		this.summaryTrigger = summaryTrigger;
		this.clock = clock;
		this.turnBudget = turnBudget;
	}

	/**
	 * 다음 턴을 만든다.
	 *
	 * @param sessionId         대상 세션
	 * @param chosenChoiceOrder 직전 턴에서 고른 선택지 순서. <b>세션의 첫 턴이면 {@code null}</b>
	 */
	public TurnOutcome advance(UUID sessionId, Integer chosenChoiceOrder) {
		return advance(sessionId, chosenChoiceOrder, null);
	}

	/**
	 * 다음 턴을 만들되 <b>직전 턴에 무엇을 골랐는지 함께 기록한다</b> (§4.3-3, B-35).
	 *
	 * @param chosenChoiceId 직전 턴이 발급한 식별자. 첫 턴이면 {@code null}
	 */
	public TurnOutcome advance(UUID sessionId, Integer chosenChoiceOrder, String chosenChoiceId) {
		PipelineContext context = this.transactions.execute(status -> readContext(sessionId));

		// ── 트랜잭션 밖 ── Provider 호출과 L2 검수 (§9.2, §13-14-a)
		//
		// #116 — 여기가 턴 예산이 열리는 유일한 자리다. 이 아래의 외부 호출은 최대 넷이고
		// (생성 · 판정 · 재생성 · 재판정) 각각에 상한이 있어도 넷의 합에는 상한이 없었다.
		// 남은 예산이 없으면 뒤 호출은 걸리지도 않는다 (TimeLimitedStoryProvider).
		Generated generated = TurnDeadline.within(
				TurnDeadline.startingNow(this.clock, this.turnBudget.budgetMs()),
				() -> generateAndScreen(context, chosenChoiceOrder));
		if (generated == null) {
			// I-2 — 통과하지 못한 본문은 여기서 끝난다. 저장도 반환도 하지 않는다.
			return blocked(context);
		}

		TurnOutcome outcome = this.transactions.execute(status -> {
			// §4.3-3 — 선택은 다음 턴이 저장되는 것과 같은 트랜잭션에서 기록된다. 나누면
			// "골랐는데 다음 턴이 없는" 또는 그 반대의 상태가 남는다.
			if (chosenChoiceId != null) {
				this.turns.findFirstBySessionIdAndDeletedAtIsNullOrderByTurnNoDesc(context.sessionId())
						.ifPresent(previous -> previous.recordChoice(chosenChoiceId,
								Instant.now(this.clock)));
			}
			return commit(context, generated);
		});

		// R4.6 — 여기서부터는 사용자 대기 시간이 아니다. 요약은 응답이 나간 뒤에 갱신된다.
		this.summaryTrigger.afterTurn(context.sessionId(), outcome.turnNo());
		return outcome;
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
		TurnRequest request = new TurnRequest(context.storyVersionId(), context.turnNo(), chosenChoiceOrder,
				generationContext(context, chosenChoiceOrder));

		GeneratedTurn result = this.provider.generateTurn(request);
		SafetyJudgement judgement = screen(result);

		if (judgement.outcome() == SafetyOutcome.PASS) {
			return new Generated(result, SafetyVerdict.PASS, judgement);
		}
		if (judgement.blocked()) {
			return null;
		}

		GeneratedTurn regenerated = this.provider.generateTurn(request);
		SafetyJudgement second = screen(regenerated);
		if (second.outcome() != SafetyOutcome.PASS) {
			return null;
		}
		return new Generated(regenerated, SafetyVerdict.REVISED, second);
	}

	/**
	 * 프롬프트의 재료를 모은다 (§5.1, B-22).
	 *
	 * <p><b>트랜잭션 밖에서 부른다.</b> Provider 호출을 트랜잭션 안에 들이지 않는다는 규칙은 그대로다.
	 *
	 * <p><b>{@code ai} 가 무엇을 실을지는 여기서 정하지 않는다.</b> 몇 턴을 원문으로 쓰고 어디서
	 * 자를지는 조립기의 예산 판단이다 (§13-2, §4.4). 여기가 정하는 것은 <b>얼마나 읽을 것인가</b>뿐이다.
	 *
	 * <p><b>그 수를 {@code summaryMerge} 에서 읽는다</b> (#97). 그보다 오래된 턴은 요약에 병합되므로
	 * 읽을 이유가 없다. 이전에는 같은 숫자 8 을 상수로 복제했고, 그러면 {@code inPrompt} 를 그보다
	 * 크게 설정했을 때 <b>조립기가 있는 만큼만 받고 그 사실을 알지 못했다.</b> 세 값은
	 * {@code RecentTurnsProperties} 가 부팅에서 함께 검증한다 ({@code verbatim ≤ inPrompt ≤ summaryMerge}).
	 */
	private GenerationContext generationContext(PipelineContext context, Integer chosenChoiceOrder) {
		List<Turn> recent = this.turns
				.findBySessionIdAndDeletedAtIsNullOrderByTurnNoDesc(context.sessionId(), Limit.of(this.recentTurns.summaryMerge()));

		return new GenerationContext(
				context.version().worldPrompt(),
				context.version().characters().stream()
						.map(character -> new GenerationContext.Character(character.name(), character.persona()))
						.toList(),
				context.state().toJson(),
				// R4.5 — 요약 파이프라인이 남긴 현재 요약. 아직 없으면 null 이다 (첫 8턴이 그렇다).
				this.summaries.findFirstBySessionIdAndDeletedAtIsNullOrderByUptoTurnNoDescCreatedAtDesc(
						context.sessionId()).map(StorySummary::getSummaryText).orElse(null),
				recent.reversed().stream().map(TurnPipeline::toRecentTurn).toList(),
				userAction(recent, chosenChoiceOrder));
	}

	/**
	 * I-1 — 이번 턴의 사용자 행동은 <b>서버가 저장해 둔 선택지 본문</b>이다.
	 *
	 * <p>클라이언트가 보낸 텍스트를 쓰지 않는다. 요청이 나른 것은 {@code choiceId} 뿐이고, 그것에서
	 * 되찾은 {@code order} 로 직전 턴의 저장된 선택지를 찾는다.
	 */
	private static String userAction(List<Turn> recent, Integer chosenChoiceOrder) {
		if (chosenChoiceOrder == null || recent.isEmpty()) {
			return null;
		}
		for (JsonNode choice : JSON.readTree(recent.getFirst().getChoices())) {
			if (choice.path("order").asInt() == chosenChoiceOrder) {
				return choice.path("text").asString(null);
			}
		}
		return null;
	}

	/**
	 * 저장된 턴 하나를 프롬프트 재료로 바꾼다.
	 *
	 * <p><b>문단을 줄바꿈으로 잇는다.</b> 저장 형태는 객체 배열이고(#84) 프롬프트에 실리는 것은
	 * 본문이다 — 종류와 화자는 렌더링의 것이지 다음 턴을 쓰는 데 필요한 정보가 아니다.
	 */
	private static GenerationContext.RecentTurn toRecentTurn(Turn turn) {
		StringBuilder body = new StringBuilder();
		for (JsonNode paragraph : JSON.readTree(turn.getParagraphs())) {
			body.append(body.isEmpty() ? "" : "\n").append(paragraph.path("text").asString(""));
		}

		String chosenText = null;
		if (turn.getChosenChoiceId() != null) {
			for (JsonNode choice : JSON.readTree(turn.getChoices())) {
				if (turn.getChosenChoiceId().equals(choice.path("choiceId").asString(null))) {
					chosenText = choice.path("text").asString(null);
				}
			}
		}

		// paragraphsDigest 는 B-34 가 만든다. 없으면 조립기가 원문을 쓴다 (TurnPromptFactory).
		return new GenerationContext.RecentTurn(turn.getTurnNo(), chosenText, body.toString(), null);
	}

	/**
	 * L2 대상은 본문과 선택지 둘 다다 (§9.1).
	 *
	 * <p><b>판정 강도는 달라지지 않는다</b> (#84). {@code SafetyL2Judge} 는 받은 것을 전부
	 * 이어 붙여 정규화하므로, 문단 하나를 넘기든 셋을 넘기든 대조 대상 문자열이 같다 — 문단 경계에
	 * 걸친 표현도 그대로 걸린다. 바뀐 것은 <b>{@code paragraphs} 라는 파라미터 이름이 드디어
	 * 사실이 됐다</b>는 점이다. 이전에는 통 문자열 하나를 담은 1개짜리 목록이었다.
	 */
	private SafetyJudgement screen(GeneratedTurn result) {
		List<String> choiceTexts = result.choices().stream().map(GeneratedChoice::text).toList();
		List<String> paragraphTexts = result.paragraphs().stream().map(GeneratedParagraph::text).toList();
		return this.safetyJudge.judge(paragraphTexts, choiceTexts);
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
		UUID endingId = ended ? endingIdOf(context.version(), endingDecision) : null;

		String choicesJson = ended
				? "[]"
				: issuedChoices(context.sessionId(), newTurnNo, generated.result());

		// R5.1 — 문단 배열을 그대로 직렬화한다. 통 문자열을 List.of(...) 로 감싸던 자리다 (#84).
		// speaker_name 은 파생값이다 — 진실의 원천은 paragraphs 다.
		Turn turn = this.turns.save(Turn.create(new Turn.TurnDraft(
				context.sessionId(), newTurnNo, chapterDecision.chapterNo(),
				JSON.writeValueAsString(generated.result().paragraphs()),
				choicesJson, generated.result().leadSpeakerName(),
				chapterDecision.changed(), ended, endingId,
				// R11.2 — 이 경로는 Provider 가 만든 본문만 저장한다 (§4.3-5·6). 다른 경로가
				// 생기면 그 경로가 자기 사실을 넣는다. 여기서 정하는 것은 이 경로의 사실이다.
				generated.verdict(), true), now));

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
	private static String issuedChoices(UUID sessionId, int turnNo, GeneratedTurn result) {
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
	 * 도달한 엔딩의 <b>실제 식별자</b>.
	 *
	 * <p>{@code turn.ending_id} 와 {@code play_session.current_ending_id} 에 저장되는 값이므로
	 * catalog 의 행을 가리켜야 한다 — 파생값을 만들어 넣으면 저장은 되지만 조회가 되지 않는다.
	 */
	private static UUID endingIdOf(StoryVersionView version, EndingEngine.EndingDecision decision) {
		int endingNo = decision.ending().endingNo();
		return version.endings().stream()
				.filter(ending -> ending.endingNo() == endingNo)
				.map(StoryVersionView.EndingView::id)
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("ending %d is not in version %s"
						.formatted(endingNo, version.storyVersionId())));
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
	private record Generated(GeneratedTurn result, SafetyVerdict verdict, SafetyJudgement judgement) {
	}
}

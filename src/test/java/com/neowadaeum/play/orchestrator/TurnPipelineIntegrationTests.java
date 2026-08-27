package com.neowadaeum.play.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.common.support.RecentTurnsProperties;
import com.neowadaeum.common.observability.SafetyMetrics;
import com.neowadaeum.common.observability.TurnMetrics;
import com.neowadaeum.common.support.TurnBudgetProperties;
import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.ai.provider.StoryProvider;
import com.neowadaeum.ai.provider.TurnOnlyStoryProvider;
import com.neowadaeum.ai.schema.TurnOutputParser;
import com.neowadaeum.play.port.TurnRequest;
import com.neowadaeum.play.port.GeneratedChoice;
import com.neowadaeum.play.port.GeneratedParagraph;
import com.neowadaeum.play.port.GeneratedTurn;
import com.neowadaeum.catalog.query.StoryVersionFacade;
import com.neowadaeum.catalog.query.StoryVersionView;
import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.domain.Turn;
import com.neowadaeum.play.domain.SessionStatus;
import com.neowadaeum.play.engine.ChapterEngine;
import com.neowadaeum.play.engine.EndingEngine;
import com.neowadaeum.play.engine.GameState;
import com.neowadaeum.play.engine.GameStateEngine;
import com.neowadaeum.play.repository.GameStateSnapshotRepository;
import com.neowadaeum.play.repository.PlaySessionRepository;
import com.neowadaeum.play.repository.StorySummaryRepository;
import com.neowadaeum.play.repository.TurnRepository;
import com.neowadaeum.safety.l2.SafetyL2Judge;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-9-1 (#64) — <b>턴이 실제로 도는지</b> 확인한다.
 *
 * <p>S-1 ~ S-8 은 각 조각을 따로 검증했다. 여기서 처음으로 시드 작품 · 결정론 Provider ·
 * 규칙 기반 L2 · 엔진 셋 · 저장이 하나로 돈다. ADR-0004 가 슬라이스의 목표로 잡은
 * <b>"M1 — 턴이 돈다"</b> 가 이 클래스가 증명하는 것이다.
 *
 * <p><b>파이프라인을 손으로 조립한다.</b> {@code @MockitoBean} 으로 Provider 를 갈아끼우면 컨텍스트
 * 캐시 키가 갈라져 컨테이너가 그 수만큼 더 뜬다({@code ContainerTestBase}). 협력자만 바꿔 새
 * 인스턴스를 만들면 컨텍스트는 한 벌로 유지된다.
 */
class TurnPipelineIntegrationTests extends ContainerTestBase {

	/** 계측은 이 테스트의 관심사가 아니다 — 값을 버리는 레지스트리로 배선만 채운다 (B-48). */
	private static final io.micrometer.core.instrument.MeterRegistry METERS =
			new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final UUID SEED_STORY = UUID.fromString("11111111-1111-4111-8111-000000000001");

	private static final UUID SEED_VERSION = UUID.fromString("11111111-1111-4111-8111-111111111111");

	private static final Clock FIXED = Clock.fixed(Instant.parse("2026-08-23T04:05:06Z"), ZoneOffset.UTC);

	@Autowired
	private PlaySessionRepository sessions;

	@Autowired
	private TurnRepository turns;

	@Autowired
	private GameStateSnapshotRepository snapshots;

	@Autowired
	private StoryVersionFacade storyVersions;

	@Autowired
	private StoryProvider provider;

	@Autowired
	private SafetyL2Judge safetyJudge;

	@Autowired
	private StorySummaryRepository summaries;

	@Autowired
	private AsyncSummaryTrigger summaryTrigger;

	@Autowired
	private GameStateEngine gameStateEngine;

	@Autowired
	private ChapterEngine chapterEngine;

	@Autowired
	private EndingEngine endingEngine;

	@Autowired
	private PlatformTransactionManager playTransactionManager;

	// ── 완주 ────────────────────────────────────────────────

	/**
	 * <b>M1 — 턴이 돈다.</b> 시드 작품이 시작부터 조건부 엔딩까지 실제로 진행된다.
	 *
	 * <p>중간의 어느 한 조각이라도 어긋나면 여기서 멈춘다 — 스키마 표기, 조건식 문법,
	 * 챕터 전환 순서, 엔딩 폴백 시점 전부가 한 번에 시험된다.
	 */
	@Test
	void S9_1_seed_story_plays_from_start_to_a_conditional_ending() {
		UUID sessionId = newSession();
		TurnPipeline pipeline = pipelineWith(this.provider);

		TurnOutcome outcome = playUntilEnd(pipeline, sessionId, 1);

		assertThat(outcome.ended()).as("조건부 엔딩에 닿지 못했다").isTrue();
		// B-45 로 엔딩이 다섯이 됐다. 시크릿은 총계에서 빠지므로 보이는 엔딩은 넷이고,
		// '첫 빛'은 그중 셋째다 (R7.11).
		assertThat(outcome.endingIndex()).isEqualTo(3);
		assertThat(outcome.totalEndings()).isEqualTo(4);

		PlaySession session = this.sessions.findById(sessionId).orElseThrow();
		assertThat(session.getStatus()).isEqualTo(SessionStatus.COMPLETED);
		assertThat(session.getCompletedAt()).isNotNull();
		assertThat(session.getCurrentEndingId()).isNotNull();
	}

	/**
	 * 저장된 {@code ending_id} 가 <b>실제 {@code ending_def} 행을 가리킨다.</b>
	 *
	 * <p>초안은 파사드가 {@code id} 를 싣지 않아 {@code (버전, 엔딩 번호)} 로 값을 만들어 넣었다.
	 * 저장은 되지만 <b>조회가 되지 않는 값</b>이었다 — 엔딩 화면·통계·History 가 전부 이 id 로
	 * 되돌아간다. 형식이 맞다고 통과하는 것이 아니라 실제 행과 대조한다.
	 */
	@Test
	void S9_1_stored_ending_id_resolves_to_a_real_ending_row() {
		UUID sessionId = newSession();
		TurnOutcome outcome = playUntilEnd(pipelineWith(this.provider), sessionId, 1);

		UUID storedEndingId = this.sessions.findById(sessionId).orElseThrow().getCurrentEndingId();

		assertThat(storedEndingId).isNotNull();
		assertThat(this.storyVersions.findByVersionId(SEED_VERSION).orElseThrow().endings())
				.extracting(StoryVersionView.EndingView::id)
				.as("저장된 ending_id 가 이 버전의 엔딩 중 하나여야 한다")
				.contains(storedEndingId);

		assertThat(this.turns.findFirstBySessionIdAndDeletedAtIsNullOrderByTurnNoDesc(sessionId).orElseThrow()
				.getEndingId())
				.as("턴에 기록된 값도 같아야 한다")
				.isEqualTo(storedEndingId);
		assertThat(outcome.endingId()).isEqualTo(storedEndingId);
	}

	/** §10.1-11 — 조건을 채우지 않는 갈래도 무한히 진행되지 않고 기본 엔딩으로 끝난다. */
	@Test
	void S10_1_11_the_other_branch_terminates_on_the_default_ending() {
		UUID sessionId = newSession();
		TurnPipeline pipeline = pipelineWith(this.provider);

		TurnOutcome outcome = playUntilEnd(pipeline, sessionId, 2);

		assertThat(outcome.ended()).isTrue();
		assertThat(outcome.turnNo()).isGreaterThan(3);
	}

	/**
	 * <b>B-44 — 시작→40턴→엔딩까지 실제 AI 없이 재현한다.</b>
	 *
	 * <p>이 작업의 DoD 다. <b>"끝났다"만 보면 3턴에 끝나도 통과한다</b> — 그래서 턴 수를 함께
	 * 단언한다. 아무 조건도 채우지 않는 갈래는 장마다 {@code max_turns} 로 밀리고(R7.2), 마지막
	 * 장의 끝에서 기본 엔딩으로 닫힌다 (R7.7). 시드의 {@code max_turns} 합계가 그 40 이다 (B-45).
	 *
	 * <p><b>중간의 어느 한 조각이라도 어긋나면 여기서 멈춘다</b> — 40번의 챕터 판정, 40번의 상태
	 * 병합, 40번의 L2, 그리고 그 사이에 도는 요약 압축(B-34)까지가 한 번에 시험된다.
	 *
	 * <p><b>I-15 — 같은 입력에 같은 결과다.</b> 결정론 Provider 는 시나리오 파일이 정한 응답만
	 * 돌려주며 난수가 없다.
	 */
	@Test
	void B44_the_seed_story_plays_forty_turns_to_the_default_ending() {
		UUID sessionId = newSession();
		TurnPipeline pipeline = pipelineWith(this.provider);

		TurnOutcome outcome = playUntilEnd(pipeline, sessionId, 2);

		assertThat(outcome.ended()).as("40턴 갈래가 엔딩에 닿지 못했다").isTrue();
		assertThat(outcome.turnNo())
				.as("'끝났다'만으로는 부족하다 — 40턴을 실제로 지났는가")
				.isEqualTo(40);

		PlaySession session = this.sessions.findById(sessionId).orElseThrow();
		assertThat(session.getStatus()).isEqualTo(SessionStatus.COMPLETED);
		assertThat(session.getChapterNo()).as("여섯 장을 전부 지나야 40턴이 된다").isEqualTo(6);
		assertThat(this.turns.findBySessionIdAndDeletedAtIsNullAndTurnNoBetweenOrderByTurnNoAsc(sessionId, 1, 40))
				.as("턴 원문은 전부 남는다 (R4.8)")
				.hasSize(40);
	}

	/** 턴마다 스냅샷이 하나씩 쌓인다 (I-5 — append-only). */
	@Test
	void I5_every_turn_appends_one_snapshot() {
		UUID sessionId = newSession();
		TurnPipeline pipeline = pipelineWith(this.provider);

		pipeline.advance(sessionId, null);
		pipeline.advance(sessionId, 1);

		GameState state = this.snapshots
				.findFirstBySessionIdAndDeletedAtIsNullOrderByTurnNoDesc(sessionId)
				.map(snapshot -> GameState.from(JSON.readTree(snapshot.getState())))
				.orElseThrow();

		assertThat(state.turn()).isEqualTo(2);
		assertThat(state.numerics()).containsKey("affinity.yuna");
	}

	// ── AI 제안값 우회 방지 (tasks.md S-9 요구사항 1) ────────

	/**
	 * R7.1 · R7.9 · I-10 — AI 제안값이 <b>서버 판정을 우회하지 못한다.</b>
	 *
	 * <p>S-7 은 엔진이 제안값을 <b>받을 파라미터가 없다</b>는 것까지 증명했다. 그러나 그것은
	 * <i>엔진이 안 받는다</i>는 증명이지 <b>파이프라인이 그 값을 다른 경로로 반영하지 않는다</b>는
	 * 증명이 아니다 — §4.3 의 6단계에서 파싱된 값이 9·10단계를 우회해 응답이나 세션 상태에 샐 수
	 * 있다. 그 경로는 여기서만 닫힌다.
	 *
	 * <p>Provider 가 <b>매 턴 "챕터를 넘겨라 · 엔딩으로 끝내라"고 주장</b>하는데, 상태 변화는
	 * 전혀 제안하지 않아 어떤 조건도 만족되지 않는 응답을 준다.
	 */
	@Test
	void R7_1_R7_9_ai_suggestions_never_override_the_server_decision() {
		UUID sessionId = newSession();
		TurnPipeline pipeline = pipelineWith(insistentProvider());

		TurnOutcome first = pipeline.advance(sessionId, null);

		assertThat(first.ended()).as("endingSuggested 로 세션이 끝나면 안 된다").isFalse();
		assertThat(first.chapterChanged()).as("chapterAdvanceSuggested 로 챕터가 넘어가면 안 된다").isFalse();
		assertThat(first.chapterNo()).isEqualTo(1);

		PlaySession session = this.sessions.findById(sessionId).orElseThrow();
		assertThat(session.getStatus()).isEqualTo(SessionStatus.ACTIVE);
		assertThat(session.getChapterNo()).isEqualTo(1);

		// 저장된 턴에도 새지 않는다 — 응답만 막고 기록이 따라가면 History 가 거짓말을 한다.
		assertThat(this.turns.findFirstBySessionIdAndDeletedAtIsNullOrderByTurnNoDesc(sessionId).orElseThrow()
				.isChapterChanged()).isFalse();
	}

	// ── 트랜잭션 경계 (B-32 DoD) ────────────────────────────

	/**
	 * §9.2 · §13-14-a — <b>Provider 호출 시점에 트랜잭션이 열려 있지 않다.</b>
	 *
	 * <p>25초짜리 트랜잭션은 커넥션 풀을 고갈시킨다. "짧은 TX → 외부 호출 → 짧은 TX" 를 지켰는지는
	 * 코드를 읽어서가 아니라 <b>호출 시점에 물어봐야</b> 확인된다.
	 */
	@Test
	void B32_no_transaction_is_open_while_the_provider_is_called() {
		UUID sessionId = newSession();
		AtomicInteger calls = new AtomicInteger();

		StoryProvider watching = new TurnOnlyStoryProvider() {
			@Override
			public String providerId() {
				return "tx-watching";
			}

			@Override
			public GeneratedTurn generateTurn(TurnRequest request) {
				assertThat(TransactionSynchronizationManager.isActualTransactionActive())
						.as("Provider 호출이 트랜잭션 안에서 일어났다 (§9.2, §13-14-a)")
						.isFalse();
				calls.incrementAndGet();
				return TurnPipelineIntegrationTests.this.provider.generateTurn(request);
			}
		};

		pipelineWith(watching).advance(sessionId, null);

		assertThat(calls.get()).isEqualTo(1);
	}

	// ── 보조 ────────────────────────────────────────────────

	private TurnOutcome playUntilEnd(TurnPipeline pipeline, UUID sessionId, int choiceOrder) {
		TurnOutcome outcome = pipeline.advance(sessionId, null);
		// B-45 이후 아무 조건도 채우지 않는 갈래는 40턴을 다 쓰고 끝난다. 여유를 조금 둔다.
		for (int guard = 0; guard < 45 && !outcome.ended(); guard++) {
			outcome = pipeline.advance(sessionId, choiceOrder);
		}
		return outcome;
	}

	/** 매 턴 전환과 종료를 주장하지만 상태는 전혀 바꾸지 않는 Provider. */
	private static StoryProvider insistentProvider() {
		return new TurnOnlyStoryProvider() {
			@Override
			public String providerId() {
				return "insistent";
			}

			@Override
			public GeneratedTurn generateTurn(TurnRequest request) {
				return new GeneratedTurn(List.of(GeneratedParagraph.narration("아무 일도 일어나지 않았다.")),
						List.of(new GeneratedChoice(1, "계속한다")),
						JSON.readTree("{}"), true, "ending-first-light");
			}
		};
	}

	// ── 관리자 자유입력 (B-43) ───────────────────────────────

	/**
	 * <b>자유입력이 프롬프트의 사용자 행동 자리에 그대로 들어간다</b> (R14.2).
	 *
	 * <p>Provider 를 들여다보는 이유는 <b>그 자리에 무엇이 실려 갔는지</b>가 이 기능의 전부이기
	 * 때문이다 — 선택지에서 왔든 자유입력에서 왔든 프롬프트가 보는 것은 하나여야 한다.
	 */
	@Test
	void R14_2_free_input_reaches_the_prompt_as_the_user_action() {
		UUID sessionId = newSession();
		java.util.List<String> seen = new java.util.ArrayList<>();
		StoryProvider watching = new TurnOnlyStoryProvider() {
			@Override
			public String providerId() {
				return "watching";
			}

			@Override
			public GeneratedTurn generateTurn(TurnRequest request) {
				seen.add(request.context().userAction());
				return new GeneratedTurn(List.of(GeneratedParagraph.narration("자유입력에 이어지는 장면.")),
						List.of(new GeneratedChoice(1, "계속한다")), JSON.readTree("{}"), false, null);
			}
		};
		TurnPipeline pipeline = pipelineWith(watching);
		pipeline.advance(sessionId, null);

		pipeline.advanceWithFreeInput(sessionId, "창밖을 본다");

		assertThat(seen).containsExactly(null, "창밖을 본다");
	}

	/**
	 * <b>만들어진 턴이 자유입력으로 표시된다</b> (R14.2).
	 *
	 * <p>표시가 없으면 나중에 그 턴이 <b>사람이 넣은 것</b>임을 알 수 없다. 본문을 만든 것은
	 * 여전히 AI 이므로 {@code is_ai_generated} 도 함께 참이다 — 둘은 다른 축이다.
	 */
	@Test
	void R14_2_a_free_input_turn_is_marked_and_an_ordinary_turn_is_not() {
		UUID sessionId = newSession();
		TurnPipeline pipeline = pipelineWith(this.provider);
		pipeline.advance(sessionId, null);

		pipeline.advanceWithFreeInput(sessionId, "창밖을 본다");

		assertThat(this.turns.findBySessionIdAndTurnNoAndDeletedAtIsNull(sessionId, 1)).get()
				.satisfies(ordinary -> assertThat(ordinary.isAdminFreeInput()).isFalse());
		assertThat(this.turns.findBySessionIdAndTurnNoAndDeletedAtIsNull(sessionId, 2)).get()
				.satisfies(free -> {
					assertThat(free.isAdminFreeInput()).isTrue();
					assertThat(free.isAiGenerated()).isTrue();
				});
	}

	private UUID newSession() {
		return this.sessions.save(PlaySession.start(UUID.randomUUID(), SEED_STORY, SEED_VERSION,
				"fixed", "scenario-v1", false, Instant.now(FIXED))).getId();
	}

	// ── 본문 손실 회귀 (#84) ─────────────────────────────────

	/**
	 * <b>#84 의 핵심 회귀 테스트</b> — 문단 3개가 <b>파싱부터 DB 저장까지</b> 그대로 남는다.
	 *
	 * <p>고정 Provider 가 만들어 둔 객체를 넘기는 것이 아니라 <b>모델이 보냈을 원시 JSON 을 실제
	 * 파서에 통과시킨다.</b> 그래야 이 테스트가 지키는 것이 계약의 모양이 아니라 <b>경로 전체</b>가
	 * 된다 — 이전 결함은 파서가 아니라 파서와 저장 <b>사이</b>에 있었다.
	 *
	 * <p>세는 것이 개수만이 아닌 것도 의도다. 개수만 보면 문단 셋을 나레이션 셋으로 바꿔도 통과하고,
	 * 그때 잃는 것은 <b>대사와 나레이션의 구분</b>(R5.2)이다. 순서 · 종류 · 화자 · 본문을 함께 건다.
	 */
	@Test
	void R5_1_three_paragraphs_survive_parsing_pipeline_and_storage() {
		UUID sessionId = newSession();
		String rawFromModel = """
				{
				  "speakerName": "유나",
				  "paragraphs": [
				    { "type": "narration", "text": "복도 끝에서 발소리가 멈췄다." },
				    { "type": "dialogue",  "text": "거기 서 있으면 문 못 열어." },
				    { "type": "narration", "text": "돌아보기 전에 그림자가 먼저 지나갔다." }
				  ],
				  "choices": [{ "order": 1, "text": "계속한다" }],
				  "stateChanges": {},
				  "chapterAdvanceSuggested": false,
				  "endingSuggested": null
				}
				""";

		TurnOutcome outcome = pipelineWith(parsingProvider(rawFromModel)).advance(sessionId, null);

		Turn stored = this.turns.findById(outcome.turnId()).orElseThrow();
		JsonNode paragraphs = JSON.readTree(stored.getParagraphs());

		assertThat(paragraphs.size()).as("문단이 저장 시점에 사라졌다 (R5.1)").isEqualTo(3);
		assertThat(paragraphs.get(0).path("type").asString()).isEqualTo("NARRATION");
		assertThat(paragraphs.get(0).path("speakerName").isNull()).as("나레이션에 화자가 붙었다").isTrue();
		assertThat(paragraphs.get(0).path("text").asString()).isEqualTo("복도 끝에서 발소리가 멈췄다.");

		assertThat(paragraphs.get(1).path("type").asString()).isEqualTo("DIALOGUE");
		assertThat(paragraphs.get(1).path("speakerName").asString())
				.as("턴 단위 화자가 대사 문단으로 복사되지 않았다 (#84 결정)")
				.isEqualTo("유나");
		assertThat(paragraphs.get(1).path("text").asString()).isEqualTo("거기 서 있으면 문 못 열어.");

		assertThat(paragraphs.get(2).path("type").asString()).isEqualTo("NARRATION");
		assertThat(paragraphs.get(2).path("text").asString()).isEqualTo("돌아보기 전에 그림자가 먼저 지나갔다.");

		assertThat(stored.getSpeakerName())
				.as("turn.speaker_name 은 문단에서 나오는 파생값이다 (#84 결정)")
				.isEqualTo("유나");
	}

	/**
	 * 이전 결함의 재현 방지 — <b>통 문자열을 1개짜리 배열로 감싸던 형태</b>가 돌아오면 실패한다.
	 *
	 * <p>{@code List.of(narrative)} 는 배열이므로 "배열인가"만 보는 단언은 그때도 통과했다.
	 * 실제로 봐야 하는 것은 <b>문단이 이어 붙지 않았는가</b>다.
	 */
	@Test
	void R5_1_paragraphs_are_not_collapsed_into_a_single_entry() {
		UUID sessionId = newSession();
		String rawFromModel = """
				{"paragraphs": [{"type": "narration", "text": "첫째 문단."},
				                {"type": "narration", "text": "둘째 문단."}],
				 "choices": [{"order": 1, "text": "계속한다"}]}
				""";

		TurnOutcome outcome = pipelineWith(parsingProvider(rawFromModel)).advance(sessionId, null);

		JsonNode paragraphs = JSON.readTree(this.turns.findById(outcome.turnId()).orElseThrow().getParagraphs());

		assertThat(paragraphs.size()).isEqualTo(2);
		assertThat(paragraphs.get(0).path("text").asString())
				.as("두 문단이 하나로 이어 붙었다")
				.doesNotContain("둘째");
	}

	/** 원시 JSON 을 실제 파서에 통과시켜 돌려주는 Provider. B-22 어댑터가 할 일을 축소한 것이다. */
	private static StoryProvider parsingProvider(String rawFromModel) {
		return new TurnOnlyStoryProvider() {
			@Override
			public String providerId() {
				return "parsing";
			}

			@Override
			public GeneratedTurn generateTurn(TurnRequest request) {
				return new TurnOutputParser().parse(rawFromModel).toGeneratedTurn();
			}
		};
	}

	private TurnPipeline pipelineWith(StoryProvider storyProvider) {
		return new TurnPipeline(this.sessions, this.turns, this.snapshots, this.storyVersions, storyProvider,
				this.safetyJudge, this.gameStateEngine, this.chapterEngine, this.endingEngine, RecentTurnsProperties.defaults(),
				this.summaries, this.summaryTrigger, this.playTransactionManager, FIXED, TurnBudgetProperties.defaults(),
				new TurnMetrics(METERS), new SafetyMetrics(METERS));
	}
}

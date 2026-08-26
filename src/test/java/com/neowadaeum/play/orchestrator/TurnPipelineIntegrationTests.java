package com.neowadaeum.play.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.neowadaeum.play.repository.TurnRepository;
import com.neowadaeum.safety.l2.RuleBasedSafetyJudge;
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
	private RuleBasedSafetyJudge safetyJudge;

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
		assertThat(outcome.endingIndex()).isEqualTo(1);
		assertThat(outcome.totalEndings()).isEqualTo(2);

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
		for (int guard = 0; guard < 40 && !outcome.ended(); guard++) {
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
				this.safetyJudge, this.gameStateEngine, this.chapterEngine, this.endingEngine,
				this.playTransactionManager, FIXED);
	}
}

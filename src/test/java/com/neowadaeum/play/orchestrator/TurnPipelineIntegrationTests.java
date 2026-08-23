package com.neowadaeum.play.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.ai.provider.StoryProvider;
import com.neowadaeum.ai.provider.TurnRequest;
import com.neowadaeum.ai.provider.TurnResult;
import com.neowadaeum.catalog.query.StoryVersionFacade;
import com.neowadaeum.play.domain.PlaySession;
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

		StoryProvider watching = new StoryProvider() {
			@Override
			public String providerId() {
				return "tx-watching";
			}

			@Override
			public TurnResult generateTurn(TurnRequest request) {
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
		return new StoryProvider() {
			@Override
			public String providerId() {
				return "insistent";
			}

			@Override
			public TurnResult generateTurn(TurnRequest request) {
				return new TurnResult("아무 일도 일어나지 않았다.",
						List.of(new TurnResult.ProposedChoice(1, "계속한다")),
						JSON.readTree("{}"), true, "ending-first-light");
			}
		};
	}

	private UUID newSession() {
		return this.sessions.save(PlaySession.start(UUID.randomUUID(), SEED_STORY, SEED_VERSION,
				"fixed", "scenario-v1", false, Instant.now(FIXED))).getId();
	}

	private TurnPipeline pipelineWith(StoryProvider storyProvider) {
		return new TurnPipeline(this.sessions, this.turns, this.snapshots, this.storyVersions, storyProvider,
				this.safetyJudge, this.gameStateEngine, this.chapterEngine, this.endingEngine,
				this.playTransactionManager, FIXED);
	}
}

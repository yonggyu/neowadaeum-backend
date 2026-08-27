package com.neowadaeum.play.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.common.support.RecentTurnsProperties;
import com.neowadaeum.common.observability.SafetyMetrics;
import com.neowadaeum.common.observability.TurnMetrics;
import com.neowadaeum.common.support.TurnBudgetProperties;
import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.ai.provider.StoryProvider;
import com.neowadaeum.ai.provider.TurnOnlyStoryProvider;
import com.neowadaeum.play.port.TurnRequest;
import com.neowadaeum.play.port.GeneratedChoice;
import com.neowadaeum.play.port.GeneratedParagraph;
import com.neowadaeum.play.port.GeneratedTurn;
import com.neowadaeum.authoring.blocklist.InMemoryBlocklistQuery;
import com.neowadaeum.catalog.query.StoryVersionFacade;
import com.neowadaeum.common.spi.BlocklistEntry;
import com.neowadaeum.common.spi.SafetyCategory;
import com.neowadaeum.common.support.TextNormalizer;
import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.engine.ChapterEngine;
import com.neowadaeum.play.engine.EndingEngine;
import com.neowadaeum.play.engine.GameStateEngine;
import com.neowadaeum.play.orchestrator.TurnOutcome;
import com.neowadaeum.play.orchestrator.AsyncSummaryTrigger;
import com.neowadaeum.play.orchestrator.TurnPipeline;
import com.neowadaeum.safety.l2.RuleBasedSafetyJudge;
import com.neowadaeum.safety.l2.SafetyL2Judge;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.play.repository.GameStateSnapshotRepository;
import com.neowadaeum.play.repository.PlaySessionRepository;
import com.neowadaeum.play.repository.StorySummaryRepository;
import com.neowadaeum.play.repository.TurnRepository;
import com.neowadaeum.ai.provider.StoryProvider;
import com.neowadaeum.ai.provider.TimeLimitedStoryProvider;
import com.neowadaeum.ai.provider.TurnOnlyStoryProvider;
import com.neowadaeum.common.observability.SafetyMetrics;
import com.neowadaeum.common.observability.TurnMetrics;
import com.neowadaeum.common.support.TurnBudgetProperties;
import com.neowadaeum.play.port.GenerationTimedOutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * S-9-3 (#67) — 가드가 <b>실제 Redis 에 대고</b> 동작하는지 확인한다.
 *
 * <p>인메모리로는 아무것도 증명되지 않는다. 이 가드들이 존재하는 이유가 <b>프로세스 간 공유</b>이기
 * 때문이다 — 인스턴스가 둘이면 인메모리 카운터는 계정당 두 배를 허용한다.
 */
class TurnResilienceIntegrationTests extends ContainerTestBase {

	/** 계측은 이 테스트의 관심사가 아니다 — 값을 버리는 레지스트리로 배선만 채운다 (B-48). */
	private static final io.micrometer.core.instrument.MeterRegistry METERS =
			new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final UUID SEED_STORY = UUID.fromString("11111111-1111-4111-8111-000000000001");

	private static final UUID SEED_VERSION = UUID.fromString("11111111-1111-4111-8111-111111111111");

	private static final Clock FIXED = Clock.fixed(Instant.parse("2026-08-23T04:05:06Z"), ZoneOffset.UTC);

	@Autowired
	private TurnGuards guards;

	@Autowired
	private StoryVersionFacade storyVersions;

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

	@Autowired
	private PlaySessionRepository sessions;

	@Autowired
	private TurnRepository turns;

	@Autowired
	private GameStateSnapshotRepository snapshots;

	@Autowired
	private SafetyL2Judge safetyJudge;

	@BeforeEach
	void clearPlayHistory() {
		this.snapshots.deleteAll();
		this.turns.deleteAll();
		this.sessions.deleteAll();
	}

	// ── 동시 생성 락 (§4.3-2) ───────────────────────────────

	/** §4.3-2 — 계정당 1개. 두 번째는 {@code 409 CONCURRENT_GENERATION} 이다. */
	@Test
	void S4_3_a_second_generation_for_the_same_account_is_rejected() {
		UUID playerRef = UUID.randomUUID();
		this.guards.acquireGenerationLock(playerRef);

		try {
			assertThatThrownBy(() -> this.guards.acquireGenerationLock(playerRef))
					.isInstanceOf(ApiException.class)
					.extracting(ex -> ((ApiException) ex).errorCode())
					.isEqualTo(ErrorCode.CONCURRENT_GENERATION);
		}
		finally {
			this.guards.releaseGenerationLock(playerRef);
		}
	}

	/**
	 * 락은 풀린다.
	 *
	 * <p>풀리지 않으면 한 번 실패한 계정이 TTL 이 지날 때까지 아무것도 못 한다 — 사용자에게는
	 * "다시 시도가 안 된다"로 보이고 원인을 짐작할 수 없다.
	 */
	@Test
	void S4_3_releasing_the_lock_allows_the_next_generation() {
		UUID playerRef = UUID.randomUUID();
		this.guards.acquireGenerationLock(playerRef);
		this.guards.releaseGenerationLock(playerRef);

		this.guards.acquireGenerationLock(playerRef);
		this.guards.releaseGenerationLock(playerRef);
	}

	/** 계정 기준이다. 세션 기준이면 한 사람이 여러 작품을 동시에 돌린다. */
	@Test
	void S4_3_the_lock_is_per_account_not_per_session() {
		UUID one = UUID.randomUUID();
		UUID other = UUID.randomUUID();

		this.guards.acquireGenerationLock(one);
		this.guards.acquireGenerationLock(other);

		this.guards.releaseGenerationLock(one);
		this.guards.releaseGenerationLock(other);
	}

	// ── §10.1-10 세이프티 차단 ──────────────────────────────

	/**
	 * §10.1-10 · §9.2 — <b>즉시차단 카테고리에서 재생성이 일어나지 않는가.</b>
	 *
	 * <p>필수 테스트의 문장을 그대로 옮겼다. S-8 은 <b>판정기</b>가 즉시차단을 고르는지까지
	 * 증명했다. 여기서 보는 것은 <b>파이프라인이 그 판정을 받고 재생성하지 않는가</b>이며,
	 * 재생성을 실제로 수행하는 주체가 파이프라인이므로 이쪽에서만 확인된다.
	 *
	 * <p><b>ADR-0001 은 이 항목을 "절대 강등하지 않는 것"으로 못박았다.</b> nightly 가 아니라
	 * PR 필수다.
	 */
	@Test
	void S10_1_10_an_immediate_block_does_not_trigger_regeneration() {
		AtomicInteger calls = new AtomicInteger();
		String blocked = "이나린";

		StoryProvider offending = new TurnOnlyStoryProvider() {
			@Override
			public String providerId() {
				return "offending";
			}

			@Override
			public GeneratedTurn generateTurn(TurnRequest request) {
				calls.incrementAndGet();
				return new GeneratedTurn(List.of(GeneratedParagraph.narration(blocked + " 이 문을 열었다.")),
						List.of(new GeneratedChoice(1, "계속한다")),
						JSON.readTree("{}"), false, null);
			}
		};

		// 2단은 부르지 않는다 — 1단이 즉시차단이기 때문이다 (§9.2). 그것을 이 판정기가 못박는다.
		SafetyL2Judge judge = new SafetyL2Judge(
				new RuleBasedSafetyJudge(new InMemoryBlocklistQuery(
						List.of(new BlocklistEntry(TextNormalizer.normalize(blocked), SafetyCategory.MINOR_SEXUAL)))),
				request -> {
					throw new AssertionError("즉시차단인데 2단이 불렸다 (§9.2)");
				});

		UUID sessionId = this.sessions.save(PlaySession.start(UUID.randomUUID(), SEED_STORY, SEED_VERSION,
				"offending", "scenario", false, Instant.now(FIXED))).getId();

		TurnPipeline pipeline = new TurnPipeline(this.sessions, this.turns, this.snapshots, this.storyVersions,
				offending, judge, this.gameStateEngine, this.chapterEngine, this.endingEngine, RecentTurnsProperties.defaults(),
				this.summaries, this.summaryTrigger, this.playTransactionManager, FIXED, TurnBudgetProperties.defaults(),
				new TurnMetrics(METERS), new SafetyMetrics(METERS));

		TurnOutcome outcome = pipeline.advance(sessionId, null);

		assertThat(outcome.status()).isEqualTo(TurnOutcome.TurnStatus.SAFETY_BLOCKED);
		assertThat(calls.get()).as("즉시차단인데 재생성이 일어났다 (§9.2)").isEqualTo(1);

		// I-2 · R6.6 — 통과하지 못한 본문은 저장되지 않고 세션도 움직이지 않는다.
		assertThat(this.turns.count()).isZero();
		assertThat(this.sessions.findById(sessionId).orElseThrow().getTurnNo()).isZero();
	}

	/**
	 * <b>턴 예산이 소진되면 세션이 움직이지 않는다</b> (#116, R6.6).
	 *
	 * <p>예산 초과는 §4.3 의 8단계(상태 병합) <b>이전</b>에서 끊긴다. 그것이 구조적으로 그런지를
	 * 여기서 확인한다 — 예산을 0 에 가깝게 열고, 시간 제한 데코레이터가 위임을 <b>시작조차 하지
	 * 않는</b> 상태를 만든다.
	 *
	 * <p><b>{@code Thread.sleep} 을 쓰지 않는다.</b> 대신 <b>읽을 때마다 앞으로 가는 시계</b>를 준다 —
	 * 고정 시계로는 아무리 작은 예산도 소진되지 않는다(남은 시간이 늘 그대로다). CI 가 그 사실을
	 * 먼저 알려 줬다: 1나노초 예산에서 Provider 가 <b>한 번 시작됐다.</b> 남은 시간이 0 이 아니면
	 * 데코레이터는 호출을 걸고 기다리다 끊을 뿐이고, 그때는 이미 어댑터가 시작한 뒤다.
	 */
	@Test
	void S116_an_exhausted_turn_budget_leaves_the_session_untouched() {
		AtomicInteger calls = new AtomicInteger();
		StoryProvider counting = new TurnOnlyStoryProvider() {
			@Override
			public String providerId() {
				return "counting";
			}

			@Override
			public GeneratedTurn generateTurn(TurnRequest request) {
				calls.incrementAndGet();
				return new GeneratedTurn(List.of(GeneratedParagraph.narration("본문")),
						List.of(new GeneratedChoice(1, "계속한다")), JSON.readTree("{}"), false, null);
			}
		};

		UUID sessionId = this.sessions.save(PlaySession.start(UUID.randomUUID(), SEED_STORY, SEED_VERSION,
				"counting", "scenario", false, Instant.now(FIXED))).getId();

		ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
		Clock passing = steppingClock();
		try {
			TurnPipeline pipeline = new TurnPipeline(this.sessions, this.turns, this.snapshots, this.storyVersions,
					new TimeLimitedStoryProvider(counting, executor, Duration.ofSeconds(25), passing),
					this.safetyJudge, this.gameStateEngine, this.chapterEngine, this.endingEngine,
					RecentTurnsProperties.defaults(), this.summaries, this.summaryTrigger,
					this.playTransactionManager, passing, new TurnBudgetProperties(Duration.ofMillis(1)),
					new TurnMetrics(METERS), new SafetyMetrics(METERS));

			assertThatThrownBy(() -> pipeline.advance(sessionId, null))
					.isInstanceOf(GenerationTimedOutException.class);
		}
		finally {
			executor.shutdownNow();
		}

		assertThat(calls.get()).as("예산이 없으면 Provider 는 시작되지도 않는다").isZero();
		assertThat(this.turns.findFirstBySessionIdAndDeletedAtIsNullOrderByTurnNoDesc(sessionId)).isEmpty();
		assertThat(this.sessions.findById(sessionId).orElseThrow().getTurnNo()).isZero();
	}

	/**
	 * 읽을 때마다 1초씩 가는 시계.
	 *
	 * <p>예산을 연 뒤 <b>다음 확인 시점에는 이미 지나 있다.</b> 실제 시간을 쓰지 않으므로 느려지지
	 * 않고, {@code sleep} 처럼 값이 흔들리지도 않는다.
	 */
	private static Clock steppingClock() {
		AtomicInteger reads = new AtomicInteger();
		Instant base = Instant.now(FIXED);
		return new Clock() {
			@Override
			public Instant instant() {
				return base.plusSeconds(reads.getAndIncrement());
			}

			@Override
			public ZoneId getZone() {
				return ZoneOffset.UTC;
			}

			@Override
			public Clock withZone(ZoneId zone) {
				return this;
			}
		};
	}

	// ── 연속 실패 쿨다운 (R6.5) ─────────────────────────────

	/** R6.5 — 연속 3회 실패면 서버가 대기를 강제한다. 클라이언트 쿨다운과 별개다. */
	@Test
	void R6_5_three_consecutive_failures_trigger_a_server_side_cooldown() {
		UUID sessionId = UUID.randomUUID();

		this.guards.requireNotCoolingDown(sessionId);
		for (int attempt = 0; attempt < 3; attempt++) {
			this.guards.recordFailure(sessionId);
		}

		assertThatThrownBy(() -> this.guards.requireNotCoolingDown(sessionId))
				.isInstanceOf(ApiException.class)
				.satisfies(ex -> {
					ApiException api = (ApiException) ex;
					assertThat(api.errorCode()).isEqualTo(ErrorCode.RETRY_COOLDOWN);
					assertThat(api.details()).containsEntry("retryAfterSeconds", 30L);
				});
	}

	/** 두 번까지는 막지 않는다. 한도가 3회라는 것이 R6.5 다. */
	@Test
	void R6_5_two_failures_do_not_trigger_the_cooldown() {
		UUID sessionId = UUID.randomUUID();

		this.guards.recordFailure(sessionId);
		this.guards.recordFailure(sessionId);

		this.guards.requireNotCoolingDown(sessionId);
	}

	/** <b>"연속"</b> 실패다. 성공하면 끊긴다 — 누적이면 오래 논 세션이 영영 막힌다. */
	@Test
	void R6_5_a_success_breaks_the_failure_streak() {
		UUID sessionId = UUID.randomUUID();

		this.guards.recordFailure(sessionId);
		this.guards.recordFailure(sessionId);
		this.guards.recordSuccess(sessionId);
		this.guards.recordFailure(sessionId);
		this.guards.recordFailure(sessionId);

		this.guards.requireNotCoolingDown(sessionId);
	}

	/** 세션마다 따로 센다. 한 세션의 실패가 다른 세션을 막으면 안 된다. */
	@Test
	void R6_5_failure_counts_are_scoped_to_a_session() {
		UUID failing = UUID.randomUUID();
		UUID healthy = UUID.randomUUID();

		for (int attempt = 0; attempt < 3; attempt++) {
			this.guards.recordFailure(failing);
		}

		this.guards.requireNotCoolingDown(healthy);
	}
}

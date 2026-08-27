package com.neowadaeum.play.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.ai.provider.StoryProvider;
import com.neowadaeum.ai.provider.TurnOnlyStoryProvider;
import com.neowadaeum.catalog.query.StoryVersionFacade;
import com.neowadaeum.common.observability.SafetyMetrics;
import com.neowadaeum.common.observability.TurnMetrics;
import com.neowadaeum.common.support.RecentTurnsProperties;
import com.neowadaeum.common.support.TurnBudgetProperties;
import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.engine.ChapterEngine;
import com.neowadaeum.play.engine.EndingEngine;
import com.neowadaeum.play.engine.GameStateEngine;
import com.neowadaeum.play.orchestrator.AsyncSummaryTrigger;
import com.neowadaeum.play.orchestrator.TurnPipeline;
import com.neowadaeum.play.port.GeneratedTurn;
import com.neowadaeum.play.port.TurnRequest;
import com.neowadaeum.play.repository.GameStateSnapshotRepository;
import com.neowadaeum.play.repository.PlaySessionRepository;
import com.neowadaeum.play.repository.StorySummaryRepository;
import com.neowadaeum.play.repository.TurnRepository;
import com.neowadaeum.safety.l2.SafetyL2Judge;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * B-46 — <b>25초 예산과 커넥션 풀이 부하에서도 성립하는가</b> (§15).
 *
 * <p>지금 예산도, "짧은 TX → 외부 호출 → 짧은 TX"(§9.2) 도 <b>설계로만 존재한다.</b> 부하가
 * 걸렸을 때 그 둘이 실제로 성립하는지는 확인된 적이 없다.
 *
 * <p><b>결정론 Provider 로 잰다.</b> 실 모델을 부르면 숫자가 <b>벤더의 그날 상태</b>를 재는 것이
 * 되고 재현되지 않는다. 지연은 스텁이 인위적으로 만든다.
 *
 * <p><b>잰 값을 파일로 남긴다.</b> 콘솔에만 찍으면 CI 로그에 실리지 않아 아무도 보지 못한다 —
 * 결과는 {@code build/perf/} 로 나가고 nightly 워크플로가 그것을 아티팩트로 올린다.
 *
 * <p>{@code nightly} 다 (ADR-0001). 부하 측정을 PR 마다 돌리면 CI 가 그것을 기다리는 데 대부분의
 * 시간을 쓴다.
 */
@org.junit.jupiter.api.Tag("nightly")
class TurnLatencyNightlyTests extends ContainerTestBase {

	/** 계측은 이 테스트의 관심사가 아니다 — 값을 버리는 레지스트리로 배선만 채운다 (B-48). */
	private static final io.micrometer.core.instrument.MeterRegistry METERS =
			new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

	private static final UUID SEED_STORY = UUID.fromString("11111111-1111-4111-8111-000000000001");

	private static final UUID SEED_VERSION = UUID.fromString("11111111-1111-4111-8111-111111111111");

	private static final Clock FIXED = Clock.fixed(Instant.parse("2026-08-23T04:05:06Z"), ZoneOffset.UTC);

	/**
	 * 모델이 이만큼 걸린다고 가정한다.
	 *
	 * <p><b>측정하려는 것은 이 값이 아니라 이 값 위에 우리가 얹는 몫이다.</b> 실 모델의 지연은
	 * 벤더가 정하고 우리가 줄일 수 없지만, 그 위의 조립·검수·저장은 우리 것이다.
	 */
	private static final Duration PROVIDER_LATENCY = Duration.ofMillis(300);

	/** 표본 수. 적으면 p95 가 한 건에 흔들리고, 많으면 nightly 가 길어진다. */
	private static final int SAMPLES = 40;

	/** 동시성. <b>커넥션 풀보다 크게 잡는다</b> — 그래야 풀이 마르는지 알 수 있다. */
	private static final int CONCURRENCY = 16;

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

	/**
	 * <b>동시 요청이 커넥션 풀을 마르게 하지 않는다</b> (§9.2).
	 *
	 * <p>Provider 호출이 트랜잭션 <b>안</b>에 있었다면 여기서 드러난다 — 동시성이 풀 크기를
	 * 넘는 순간 커넥션이 300ms 씩 붙들리고, 뒤의 요청은 커넥션을 얻지 못한다.
	 *
	 * <p>측정치는 {@code build/perf/turn-latency.md} 로 나간다.
	 */
	@Test
	void S15_the_pipeline_survives_concurrency_beyond_the_pool() throws Exception {
		TurnPipeline pipeline = pipelineWith(slowProvider());
		List<UUID> sessionIds = new ArrayList<>();
		for (int i = 0; i < CONCURRENCY; i++) {
			sessionIds.add(newSession());
		}

		List<Long> elapsedMillis = new ArrayList<>();
		try (ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY)) {
			for (int round = 0; round < SAMPLES / CONCURRENCY; round++) {
				List<Callable<Long>> batch = new ArrayList<>();
				for (UUID sessionId : sessionIds) {
					batch.add(() -> timeOneTurn(pipeline, sessionId));
				}
				for (Future<Long> done : executor.invokeAll(batch)) {
					elapsedMillis.add(done.get());
				}
			}
		}

		// 모든 요청이 끝났다. 하나라도 커넥션을 얻지 못했다면 예외로 끝났을 것이다.
		assertThat(elapsedMillis).hasSize((SAMPLES / CONCURRENCY) * CONCURRENCY);

		Percentiles totals = Percentiles.of(elapsedMillis);
		// **우리 몫**은 전체에서 Provider 가 쓴 시간을 뺀 것이다.
		assertThat(totals.p50()).isGreaterThanOrEqualTo(PROVIDER_LATENCY.toMillis());
		writeReport(totals);
	}

	/**
	 * <b>예산을 넘기면 끊긴다. 그리고 세션은 그대로다</b> (R6.6).
	 *
	 * <p>끊기는 것만으로는 부족하다 — 끊긴 뒤 세션의 턴 번호가 올라가 있으면 사용자는
	 * <b>보지 못한 턴</b>을 지나친 것이 된다.
	 */
	@Test
	void R6_6_a_turn_over_budget_is_cut_and_leaves_the_session_untouched() {
		UUID sessionId = newSession();
		int before = this.sessions.findById(sessionId).orElseThrow().getTurnNo();

		TurnPipeline pipeline = new TurnPipeline(this.sessions, this.turns, this.snapshots,
				this.storyVersions, slowProvider(), this.safetyJudge, this.gameStateEngine,
				this.chapterEngine, this.endingEngine, RecentTurnsProperties.defaults(), this.summaries,
				this.summaryTrigger, this.playTransactionManager, FIXED,
				// 예산을 Provider 지연보다 짧게 잡는다. 25초를 실제로 기다릴 이유가 없다 —
				// 확인하려는 것은 **예산이 지켜지는가**이지 그 값이 얼마인가가 아니다.
				new TurnBudgetProperties(Duration.ofMillis(50)), new TurnMetrics(METERS),
				new SafetyMetrics(METERS));

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> pipeline.advance(sessionId, null))
				.isInstanceOf(RuntimeException.class);

		assertThat(this.sessions.findById(sessionId).orElseThrow().getTurnNo()).isEqualTo(before);
		assertThat(this.turns.findFirstBySessionIdAndDeletedAtIsNullOrderByTurnNoDesc(sessionId))
				.isEmpty();
	}

	private long timeOneTurn(TurnPipeline pipeline, UUID sessionId) {
		long startedAt = System.nanoTime();
		pipeline.advance(sessionId, chosenOrderFor(sessionId));
		return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
	}

	/** 첫 턴이면 {@code null}, 이후에는 1번 선택지를 계속 고른다. */
	private Integer chosenOrderFor(UUID sessionId) {
		return (this.sessions.findById(sessionId).orElseThrow().getTurnNo() == 0) ? null : 1;
	}

	private TurnPipeline pipelineWith(StoryProvider storyProvider) {
		return new TurnPipeline(this.sessions, this.turns, this.snapshots, this.storyVersions,
				storyProvider, this.safetyJudge, this.gameStateEngine, this.chapterEngine,
				this.endingEngine, RecentTurnsProperties.defaults(), this.summaries, this.summaryTrigger,
				this.playTransactionManager, FIXED, TurnBudgetProperties.defaults(),
				new TurnMetrics(METERS), new SafetyMetrics(METERS));
	}

	/** 결정론 응답에 <b>인위적인 지연</b>만 얹는다. 내용은 시나리오 그대로다. */
	private StoryProvider slowProvider() {
		return new TurnOnlyStoryProvider() {
			@Override
			public String providerId() {
				return "slow";
			}

			@Override
			public GeneratedTurn generateTurn(TurnRequest request) {
				try {
					Thread.sleep(PROVIDER_LATENCY);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(ex);
				}
				return TurnLatencyNightlyTests.this.provider.generateTurn(request);
			}
		};
	}

	private UUID newSession() {
		return this.sessions.save(PlaySession.start(UUID.randomUUID(), SEED_STORY, SEED_VERSION,
				"slow", "scenario", false, Instant.now(FIXED))).getId();
	}

	/**
	 * 결과를 파일로 남긴다.
	 *
	 * <p><b>조건 없는 숫자는 근거가 아니다</b> — 동시성 · Provider 지연 · 표본 수를 함께 적는다.
	 * 이 파일을 읽는 사람이 다시 해석하지 않아도 되게 한다.
	 */
	private void writeReport(Percentiles totals) throws IOException {
		Path out = Path.of("build", "perf");
		Files.createDirectories(out);
		String report = """
				# 턴 지연 실측 (B-46)

				| 조건 | 값 |
				|---|---|
				| Provider | 결정론 스텁 + 인위적 지연 %d ms |
				| 동시성 | %d |
				| 표본 | %d |

				| 지표 | 전체 (ms) | Provider 를 뺀 몫 (ms) |
				|---|---|---|
				| p50 | %d | %d |
				| p95 | %d | %d |
				| p99 | %d | %d |
				| max | %d | %d |
				"""
				.formatted(PROVIDER_LATENCY.toMillis(), CONCURRENCY, totals.count(), totals.p50(),
						minusProvider(totals.p50()), totals.p95(), minusProvider(totals.p95()),
						totals.p99(), minusProvider(totals.p99()), totals.max(),
						minusProvider(totals.max()));
		Files.writeString(out.resolve("turn-latency.md"), report, StandardCharsets.UTF_8);
	}

	private static long minusProvider(long total) {
		return Math.max(0, total - PROVIDER_LATENCY.toMillis());
	}

	/** 표본에서 백분위를 뽑는다. 보간하지 않는다 — 표본이 적을 때 보간은 없는 정밀도를 지어낸다. */
	private record Percentiles(int count, long p50, long p95, long p99, long max) {

		static Percentiles of(List<Long> samples) {
			List<Long> sorted = samples.stream().sorted().toList();
			return new Percentiles(sorted.size(), at(sorted, 0.50), at(sorted, 0.95), at(sorted, 0.99),
					sorted.getLast());
		}

		private static long at(List<Long> sorted, double quantile) {
			int index = (int) Math.ceil(quantile * sorted.size()) - 1;
			return sorted.get(Math.clamp(index, 0, sorted.size() - 1));
		}
	}
}

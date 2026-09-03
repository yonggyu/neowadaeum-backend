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
import java.time.temporal.ChronoUnit;
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
 * <p><b>한 회차만으로는 백분위를 말할 수 없다.</b> 그래서 이 테스트는 두 가지를 함께 한다 —
 * 표본을 한 건이 지표를 정하지 못할 만큼 늘리고({@link #SAMPLES}), 회차를 <b>기계가 읽는 한 줄</b>로
 * 내보내 누적 표에 쌓이게 한다({@code build/perf/turn-latency-run.md}). 앞의 것이 회차 <b>안</b>의
 * 흔들림을, 뒤의 것이 회차 <b>사이</b>의 흔들림을 보이게 한다 (#255).
 *
 * <p><b>예산 초과는 여기서 다시 재지 않는다.</b> {@code TurnResilienceIntegrationTests} 가
 * 이미 그것을 본다 — 예산이 없으면 Provider 가 시작되지도 않고, 세션의 턴 번호도 그대로다
 * (R6.6). 같은 것을 두 곳에서 확인하면 <b>둘이 갈라지는 날</b>이 온다.
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

	/** 동시성. <b>커넥션 풀보다 크게 잡는다</b> — 그래야 풀이 마르는지 알 수 있다. */
	private static final int CONCURRENCY = 16;

	/**
	 * 한 세션에서 뽑는 표본 수.
	 *
	 * <p><b>늘릴 수 없다.</b> 결정론 시나리오에서 선택지 1을 계속 고르면 다섯 번째 턴이 엔딩이고
	 * ({@code demo-first-day.json}), 끝난 세션은 더 진행되지 않는다. 표본은 <b>세션을 더 만들어</b> 늘린다.
	 *
	 * <p>2 인 것은 측정 1·2 와 같은 값이기도 하다 — 첫 턴(이력 없음)과 두 번째 턴(직전 턴 있음)이
	 * 1:1 로 섞인다. 이 비율을 바꾸면 회차 간 비교가 끊긴다.
	 */
	private static final int TURNS_PER_SESSION = 2;

	/** 세션 묶음 수. 한 묶음은 {@link #CONCURRENCY} 개의 세션을 새로 만들어 끝까지 쓴다. */
	private static final int SESSION_BATCHES = 16;

	/**
	 * 표본 수 — <b>512</b>.
	 *
	 * <p><b>왜 512 인가.</b> 백분위를 보간하지 않으므로 p95 는 정렬된 표본의
	 * {@code ceil(0.95n)-1} 번째 한 건이다. 그 값이 흔들리지 않으려면 <b>그 위에 표본이 여럿
	 * 있어야</b> 한다. 512 에서 p95 위에 25건, p99 위에 5건이 남는다 — 느린 한 건이 빠져도
	 * p95 는 한 칸 옆으로 움직일 뿐이고, <b>p99 와 max 가 구조적으로 갈라진다.</b>
	 * 32 에서는 p95 위에 1건뿐이라 p95 · p99 · max 가 같은 한 표본을 가리켰다 (#255).
	 *
	 * <p>p99 위에 5건을 남기는 가장 작은 표본 수가 500 이며, 512 는 거기서 위 셋의 곱으로
	 * 떨어지는 값이다.
	 *
	 * <p><b>곱으로 적는다.</b> 앞서 이 상수는 40 이었고 {@code SAMPLES / CONCURRENCY} 의 나머지가
	 * 잘려 실제로는 32건이 돌았다 — 나눗셈으로 적으면 선언과 실제가 조용히 갈라진다.
	 */
	private static final int SAMPLES = SESSION_BATCHES * CONCURRENCY * TURNS_PER_SESSION;

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
	 * <p>측정치는 {@code build/perf/turn-latency.md} 로, 회차 한 줄은
	 * {@code build/perf/turn-latency-run.md} 로 나간다.
	 */
	@Test
	void S15_the_pipeline_survives_concurrency_beyond_the_pool() throws Exception {
		TurnPipeline pipeline = pipelineWith(slowProvider());

		List<Long> elapsedMillis = new ArrayList<>();
		try (ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY)) {
			for (int batch = 0; batch < SESSION_BATCHES; batch++) {
				elapsedMillis.addAll(runOneBatch(pipeline, executor));
			}
		}

		// 모든 요청이 끝났다. 하나라도 커넥션을 얻지 못했다면 예외로 끝났을 것이다.
		assertThat(elapsedMillis).hasSize(SAMPLES);

		Percentiles totals = Percentiles.of(elapsedMillis);
		// **우리 몫**은 전체에서 Provider 가 쓴 시간을 뺀 것이다.
		assertThat(totals.p50()).isGreaterThanOrEqualTo(PROVIDER_LATENCY.toMillis());
		// 한 표본이 지표를 결정하지 않는다 — 이 표본 수를 고른 이유다 (#255).
		assertThat(totals.above(0.99)).isGreaterThanOrEqualTo(5);
		writeReport(totals);
	}

	/**
	 * 새 세션 {@link #CONCURRENCY} 개를 만들어 각각 {@link #TURNS_PER_SESSION} 턴을 돌린다.
	 *
	 * <p>세션을 묶음마다 새로 만드는 것이 이 측정의 제약이다 — 시나리오가 다섯 번째 턴에서
	 * 엔딩에 닿으므로 <b>같은 세션을 계속 밀어 표본을 늘릴 수 없다.</b>
	 */
	private List<Long> runOneBatch(TurnPipeline pipeline, ExecutorService executor) throws Exception {
		List<UUID> sessionIds = new ArrayList<>();
		for (int i = 0; i < CONCURRENCY; i++) {
			sessionIds.add(newSession());
		}

		List<Long> elapsedMillis = new ArrayList<>();
		for (int turn = 0; turn < TURNS_PER_SESSION; turn++) {
			List<Callable<Long>> concurrent = new ArrayList<>();
			for (UUID sessionId : sessionIds) {
				concurrent.add(() -> timeOneTurn(pipeline, sessionId));
			}
			for (Future<Long> done : executor.invokeAll(concurrent)) {
				elapsedMillis.add(done.get());
			}
		}
		return elapsedMillis;
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
	 * 결과를 파일 <b>둘</b>로 남긴다.
	 *
	 * <p>{@code turn-latency.md} 는 <b>읽는 사람</b>의 것이다. <b>조건 없는 숫자는 근거가 아니므로</b>
	 * 동시성 · Provider 지연 · 표본 수를 함께 적는다.
	 *
	 * <p>{@code turn-latency-run.md} 는 <b>기계</b>의 것이다 — 이 회차 한 줄이 마크다운 표의 행
	 * 모양으로 들어 있고, nightly 워크플로가 그 한 줄을 누적 표에 덧붙인다. 회차마다 사람이 값을
	 * 옮겨 적어야 비교가 되던 것을 없앤다 (#255).
	 *
	 * <p><b>열 정의를 여기 한 곳에 둔다.</b> 머리글까지 이 파일이 함께 내보내므로, 열이 늘어도
	 * 워크플로를 고치지 않는다 — 워크플로는 "첫 두 줄은 머리글, 마지막 줄은 행"만 안다.
	 */
	private void writeReport(Percentiles totals) throws IOException {
		Path out = Path.of("build", "perf");
		Files.createDirectories(out);
		// 한 번만 읽는다 — 두 파일이 같은 회차를 가리켜야 한다.
		String measuredAt = measuredAt();
		String commit = commit();
		String runId = runId();
		String report = """
				# 턴 지연 실측 (B-46)

				| 조건 | 값 |
				|---|---|
				| 회차 (UTC) | %s |
				| 커밋 | %s |
				| 실행 | %s |
				| Provider | 결정론 스텁 + 인위적 지연 %d ms |
				| 동시성 | %d |
				| 세션당 턴 | %d |
				| 표본 | %d |

				| 지표 | 전체 (ms) | Provider 를 뺀 몫 (ms) |
				|---|---|---|
				| p50 | %d | %d |
				| p95 | %d | %d |
				| p99 | %d | %d |
				| max | %d | %d |

				백분위는 보간하지 않는다. **p95 위에 표본 %d건, p99 위에 %d건**이 있다 — 느린 한 건이
				세 지표를 함께 정하지 못한다는 뜻이며, 표본 수를 이 값으로 정한 이유다 (#255).
				"""
				.formatted(measuredAt, commit, runId, PROVIDER_LATENCY.toMillis(), CONCURRENCY,
						TURNS_PER_SESSION, totals.count(), totals.p50(), minusProvider(totals.p50()),
						totals.p95(), minusProvider(totals.p95()), totals.p99(),
						minusProvider(totals.p99()), totals.max(), minusProvider(totals.max()),
						totals.above(0.95), totals.above(0.99));
		Files.writeString(out.resolve("turn-latency.md"), report, StandardCharsets.UTF_8);

		// 전체 = 서버 몫 + Provider 지연이므로 누적 표에는 서버 몫만 싣는다. 조건 열을 함께
		// 싣는 것은 **조건이 다른 회차를 같은 열에서 비교하지 않기 위해서다.**
		String row = """
				| 회차 (UTC) | 커밋 | 실행 | 표본 | 동시성 | Provider ms | 서버 p50 | 서버 p95 | 서버 p99 | 서버 max |
				|---|---|---|---|---|---|---|---|---|---|
				| %s | %s | %s | %d | %d | %d | %d | %d | %d | %d |
				"""
				.formatted(measuredAt, commit, runId, totals.count(), CONCURRENCY,
						PROVIDER_LATENCY.toMillis(), minusProvider(totals.p50()),
						minusProvider(totals.p95()), minusProvider(totals.p99()),
						minusProvider(totals.max()));
		Files.writeString(out.resolve("turn-latency-run.md"), row, StandardCharsets.UTF_8);
	}

	private static long minusProvider(long total) {
		return Math.max(0, total - PROVIDER_LATENCY.toMillis());
	}

	/**
	 * 회차를 식별한다.
	 *
	 * <p><b>도메인 시계({@link #FIXED})가 아니라 실제 시각이다</b> — 이 값은 게임 상태가 아니라
	 * "언제 잰 회차인가"이고, 회차를 세로로 읽으려면 그것이 필요하다.
	 */
	private static String measuredAt() {
		return Instant.now().truncatedTo(ChronoUnit.MINUTES).toString();
	}

	/** 러너 밖에서 돌면 {@code local} 이다. 회차 표에서 손으로 돌린 값을 구분하기 위한 것뿐이다. */
	private static String commit() {
		String sha = System.getenv("GITHUB_SHA");
		return (sha == null || sha.isBlank()) ? "local" : sha.substring(0, Math.min(7, sha.length()));
	}

	private static String runId() {
		String runId = System.getenv("GITHUB_RUN_ID");
		return (runId == null || runId.isBlank()) ? "local" : runId;
	}

	/**
	 * 표본에서 백분위를 뽑는다. 보간하지 않는다 — 표본이 적을 때 보간은 없는 정밀도를 지어낸다.
	 *
	 * <p>표본이 512건이 된 지금도 보간하지 않는 것은 <b>측정 1·2 와 같은 방법으로 세기 위해서다.</b>
	 * 추정기를 바꾸면 회차 간 차이가 코드 변화인지 서비스 변화인지 구분되지 않는다.
	 */
	private record Percentiles(int count, long p50, long p95, long p99, long max) {

		static Percentiles of(List<Long> samples) {
			List<Long> sorted = samples.stream().sorted().toList();
			return new Percentiles(sorted.size(), at(sorted, 0.50), at(sorted, 0.95), at(sorted, 0.99),
					sorted.getLast());
		}

		private static long at(List<Long> sorted, double quantile) {
			return sorted.get(Math.clamp(index(quantile, sorted.size()), 0, sorted.size() - 1));
		}

		/**
		 * 그 백분위 <b>위</b>에 남는 표본 수.
		 *
		 * <p>이것이 1이면 그 백분위는 한 표본이 정한 값이다 — 표본 32건에서 실제로 그랬다 (#255).
		 */
		int above(double quantile) {
			return this.count - 1 - Math.clamp(index(quantile, this.count), 0, this.count - 1);
		}

		private static int index(double quantile, int count) {
			return (int) Math.ceil(quantile * count) - 1;
		}
	}
}

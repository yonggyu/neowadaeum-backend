package com.neowadaeum.play.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.ai.provider.StoryProvider;
import com.neowadaeum.ai.provider.TurnOnlyStoryProvider;
import com.neowadaeum.ai.provider.TurnRequest;
import com.neowadaeum.ai.provider.TurnResult;
import com.neowadaeum.catalog.query.StoryVersionFacade;
import com.neowadaeum.common.web.IdempotencyStore;
import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.engine.ChapterEngine;
import com.neowadaeum.play.engine.EndingEngine;
import com.neowadaeum.play.engine.GameStateEngine;
import com.neowadaeum.play.orchestrator.TurnPipeline;
import com.neowadaeum.play.repository.GameStateSnapshotRepository;
import com.neowadaeum.play.repository.PlaySessionRepository;
import com.neowadaeum.play.repository.TurnRepository;
import com.neowadaeum.safety.l2.RuleBasedSafetyJudge;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-9-3 (#67) — §10.1 의 7 · 8 번. <b>동시 요청을 실제로 만든다.</b>
 *
 * <p><b>{@code @Tag("nightly")} 인 근거는 ADR-0001 이다</b> — 두 항목은 인프라와 동시성을 요구해
 * 비싸고, 보호 대상(중복 과금 · 데이터 무결성)의 손실이 유저 0명 동안 0 이다. <b>승격 시점은 B-33</b>
 * 이며 그 PR 이 이 코드를 건드리면 태그를 뗀다.
 *
 * <p>ADR-0001 은 *"아무도 안 보는 nightly 는 없는 것과 같다"* 고 못박고 있다. 이 클래스가 그
 * 워크플로의 <b>첫 대상</b>이다 — 지금까지 nightly 대상은 0건이었다.
 */
@Tag("nightly")
class TurnConcurrencyNightlyTests extends ContainerTestBase {

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

	@Autowired
	private TurnGuards guards;

	@Autowired
	private IdempotencyStore idempotency;

	@BeforeEach
	void clearPlayHistory() {
		this.snapshots.deleteAll();
		this.turns.deleteAll();
		this.sessions.deleteAll();
	}

	/**
	 * §10.1-7 · R6.2 — <b>동일 키 동시 2회 요청 시 Provider 호출이 1회인가.</b>
	 *
	 * <p>필수 테스트의 문장을 그대로 옮겼다. 보호 대상은 중복 과금이며, 와이어프레임의 "다시 시도"가
	 * 같은 {@code choiceId} 를 재전송하므로(R6.3) 실제로 일어나는 경우다.
	 */
	@Test
	void S10_1_7_two_concurrent_identical_requests_call_the_provider_once() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		Fixture fixture = fixtureCounting(calls);
		String choiceId = firstChoiceId(fixture);

		List<Outcome> outcomes = runConcurrently(
				() -> attempt(fixture, choiceId, 1),
				() -> attempt(fixture, choiceId, 1));

		assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(2);
		assertThat(calls.get()).as("같은 요청인데 Provider 가 두 번 불렸다 — 두 번 청구된다").isEqualTo(1);
		assertThat(this.turns.count()).as("턴이 두 개 생기면 안 된다").isEqualTo(2);
	}

	/**
	 * §10.1-8 · I-6 — <b>동일 {@code turnNo} 동시 요청 중 1건만 성공하는가.</b>
	 *
	 * <p>서로 다른 선택지를 같은 턴 번호로 동시에 보낸다. 멱등 키가 다르므로 둘 다 생성 경로에
	 * 들어가려 하고, 계정당 1개인 동시 생성 락(§4.3-2)이 그중 하나를 거절한다.
	 */
	@Test
	void S10_1_8_only_one_of_two_concurrent_turns_at_the_same_number_succeeds() throws Exception {
		Fixture fixture = fixtureCounting(new AtomicInteger());
		List<String> choiceIds = firstChoiceIds(fixture);

		List<Outcome> outcomes = runConcurrently(
				() -> attempt(fixture, choiceIds.get(0), 1),
				() -> attempt(fixture, choiceIds.get(1), 1));

		assertThat(outcomes).filteredOn(Outcome::succeeded)
				.as("같은 턴 번호로 두 건이 성공하면 낙관적 잠금이 무의미하다")
				.hasSize(1);

		assertThat(this.sessions.findById(fixture.sessionId()).orElseThrow().getTurnNo())
				.as("세션이 한 번만 전진해야 한다")
				.isEqualTo(2);
	}

	// ── 보조 ────────────────────────────────────────────────

	private List<Outcome> runConcurrently(Callable<Outcome> first, Callable<Outcome> second) throws Exception {
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<Outcome> left = executor.submit(first);
			Future<Outcome> right = executor.submit(second);
			return List.of(left.get(), right.get());
		}
	}

	private Outcome attempt(Fixture fixture, String choiceId, int turnNo) {
		try {
			fixture.service().advance(fixture.playerRef(), fixture.sessionId(),
					new TurnRequestBody(choiceId, turnNo, null));
			return new Outcome(true);
		}
		catch (RuntimeException ex) {
			return new Outcome(false);
		}
	}

	private String firstChoiceId(Fixture fixture) {
		return firstChoiceIds(fixture).get(0);
	}

	private List<String> firstChoiceIds(Fixture fixture) {
		return this.turns.findFirstBySessionIdAndDeletedAtIsNullOrderByTurnNoDesc(fixture.sessionId())
				.map(turn -> {
					List<String> ids = new java.util.ArrayList<>();
					JSON.readTree(turn.getChoices())
							.forEach(choice -> ids.add(choice.path("choiceId").asString()));
					return ids;
				})
				.orElseThrow();
	}

	/** 세션과 첫 턴을 만들고, Provider 호출 수를 세는 파이프라인을 붙인다. */
	private Fixture fixtureCounting(AtomicInteger calls) {
		StoryProvider counting = new TurnOnlyStoryProvider() {
			@Override
			public String providerId() {
				return "counting";
			}

			@Override
			public TurnResult generateTurn(TurnRequest request) {
				calls.incrementAndGet();
				return TurnConcurrencyNightlyTests.this.provider.generateTurn(request);
			}
		};

		TurnPipeline pipeline = new TurnPipeline(this.sessions, this.turns, this.snapshots, this.storyVersions,
				counting, this.safetyJudge, this.gameStateEngine, this.chapterEngine, this.endingEngine,
				this.playTransactionManager, FIXED);
		PlayTurnService service = new PlayTurnService(this.sessions, this.turns, this.storyVersions, pipeline,
				this.guards, this.idempotency);

		UUID playerRef = UUID.randomUUID();
		PlaySession session = this.sessions.save(PlaySession.start(playerRef, SEED_STORY, SEED_VERSION,
				"counting", "scenario", false, Instant.now(FIXED)));

		pipeline.advance(session.getId(), null);
		calls.set(0);

		return new Fixture(playerRef, session.getId(), service);
	}

	private record Fixture(UUID playerRef, UUID sessionId, PlayTurnService service) {
	}

	private record Outcome(boolean succeeded) {
	}
}

package com.neowadaeum.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.catalog.repository.EndingStatRepository;
import com.neowadaeum.common.spi.EndingStatAggregation;
import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.repository.GameStateSnapshotRepository;
import com.neowadaeum.play.repository.PlaySessionRepository;
import com.neowadaeum.play.repository.TurnRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * B-39 — 도달률은 <b>배치가</b> 갱신한다 (R2.7, I-20, ADR-0003).
 *
 * <p>세 스토어가 걸린다 — 완주 세션은 play, 엔딩 정의와 통계 표는 catalog, 실행은 batch 다.
 * <b>batch 는 앞의 둘을 참조하지 않으며</b> 그것을 {@code ModuleStructureTests} 가 강제한다.
 */
class EndingStatBatchIntegrationTests extends ContainerTestBase {

	private static final UUID SEED_STORY = UUID.fromString("11111111-1111-4111-8111-000000000001");

	private static final UUID SEED_VERSION = UUID.fromString("11111111-1111-4111-8111-111111111111");

	/** V5 시드의 2번 엔딩(비시크릿). */
	private static final UUID ENDING_TWO = UUID.fromString("11111111-1111-4111-8111-0000000000e4");

	/** V5 시드의 1번 엔딩(시크릿). 시크릿도 <b>집계에는 들어간다</b> — 숨기는 것은 노출이다. */
	private static final UUID ENDING_SECRET = UUID.fromString("11111111-1111-4111-8111-0000000000e3");

	private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

	@Autowired
	private EndingStatAggregation aggregation;

	@Autowired
	private EndingStatRepository stats;

	@Autowired
	private PlaySessionRepository sessions;

	@Autowired
	private TurnRepository turns;

	@Autowired
	private GameStateSnapshotRepository snapshots;

	@BeforeEach
	void clear() {
		this.snapshots.deleteAll();
		this.turns.deleteAll();
		this.sessions.deleteAll();
		this.stats.deleteAll();
	}

	@AfterEach
	void clearStats() {
		this.stats.deleteAll();
	}

	/** §2.6 — 집계 키가 {@code (story_id, ending_no)} 다. {@code ending_id} 가 아니다. */
	@Test
	void S2_6_the_batch_writes_one_row_per_story_and_ending_number() {
		completeSession(ENDING_TWO);
		completeSession(ENDING_TWO);

		int rows = this.aggregation.refresh();

		assertThat(rows).isEqualTo(1);
		assertThat(this.stats.findByStoryId(SEED_STORY)).singleElement().satisfies(stat -> {
			assertThat(stat.getEndingNo()).isEqualTo(2);
			assertThat(stat.getReachedCount()).isEqualTo(2);
			assertThat(stat.getTotalCompletedCount()).isEqualTo(2);
		});
	}

	/** 분모는 그 작품의 완주 세션 전체다 (R2.8) — 엔딩이 여럿이어도 같은 값이다. */
	@Test
	void R2_8_the_denominator_is_every_completed_session_of_the_story() {
		completeSession(ENDING_TWO);
		completeSession(ENDING_SECRET);
		completeSession(ENDING_SECRET);

		this.aggregation.refresh();

		assertThat(this.stats.findByStoryId(SEED_STORY)).hasSize(2)
				.allSatisfy(stat -> assertThat(stat.getTotalCompletedCount()).isEqualTo(3));
	}

	/**
	 * <b>완주하지 않은 세션과 지운 세션은 세지 않는다.</b>
	 *
	 * <p>전자는 아직 결과가 아니고 후자는 사용자가 없앤 것이다.
	 */
	@Test
	void R2_7_unfinished_and_deleted_sessions_are_not_counted() {
		this.sessions.save(newSession());
		PlaySession deleted = completeSession(ENDING_TWO);
		deleted.deleteBy(NOW);
		this.sessions.saveAndFlush(deleted);

		assertThat(this.aggregation.refresh()).isZero();
		assertThat(this.stats.count()).isZero();
	}

	/**
	 * <b>다시 돌려도 값이 누적되지 않는다.</b>
	 *
	 * <p>집계는 누적이 아니라 <b>다시 계산</b>이다 — 그래서 한 회차를 걸러도 값이 어긋나지 않고,
	 * 배치가 실패를 삼켜도 안전하다.
	 */
	@Test
	void R2_7_running_twice_does_not_double_the_counts() {
		completeSession(ENDING_TWO);

		this.aggregation.refresh();
		this.aggregation.refresh();

		assertThat(this.stats.findByStoryId(SEED_STORY)).singleElement()
				.satisfies(stat -> assertThat(stat.getReachedCount()).isEqualTo(1));
	}

	/** 완주 세션이 없으면 아무것도 쓰지 않는다 — 빈 집계가 0 행을 만들지 않는다. */
	@Test
	void R2_7_nothing_is_written_without_completed_sessions() {
		assertThat(this.aggregation.refresh()).isZero();
	}

	// ── 보조 ────────────────────────────────────────────────

	private PlaySession completeSession(UUID endingId) {
		PlaySession session = this.sessions.save(newSession());
		session.complete(endingId, NOW);
		return this.sessions.saveAndFlush(session);
	}

	/** 같은 회원이 같은 작품에 active 를 둘 가질 수 없으므로(§13-9) 매번 새 회원으로 만든다. */
	private PlaySession newSession() {
		return PlaySession.start(UUID.randomUUID(), SEED_STORY, SEED_VERSION, "fixed", "scenario", false,
				NOW);
	}
}

package com.neowadaeum.play.debug;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.play.domain.GameStateSnapshot;
import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.domain.SafetyVerdict;
import com.neowadaeum.play.domain.SessionStatus;
import com.neowadaeum.play.domain.StorySummary;
import com.neowadaeum.play.domain.Turn;
import com.neowadaeum.play.repository.GameStateSnapshotRepository;
import com.neowadaeum.play.repository.PlaySessionRepository;
import com.neowadaeum.play.repository.StorySummaryRepository;
import com.neowadaeum.play.repository.TurnRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * B-42 — <b>스냅샷과 요약은 함께 되돌아간다</b> (R14.4).
 *
 * <p>이 테스트가 지키는 것은 한 문장이다: <b>요약만 남는 상태를 만들 수 없다.</b> 요약이 12턴
 * 까지의 이야기를 담았는데 게임 상태가 8턴의 것이면, AI 는 있지도 않았던 전개를 전제로 글을 쓴다.
 */
class SessionRollbackIntegrationTests extends ContainerTestBase {

	private static final UUID STORY_ID = UUID.fromString("11111111-1111-4111-8111-000000000001");

	private static final UUID VERSION_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

	private static final UUID PLAYER_REF = UUID.fromString("00000000-0000-4000-8000-0000000000c1");

	@Autowired
	private SessionRollbackFacade rollback;

	@Autowired
	private PlaySessionRepository sessions;

	@Autowired
	private TurnRepository turns;

	@Autowired
	private GameStateSnapshotRepository snapshots;

	@Autowired
	private StorySummaryRepository summaries;

	@AfterEach
	void clear() {
		this.summaries.deleteAll();
		this.snapshots.deleteAll();
		this.turns.deleteAll();
		this.sessions.findAll().stream().filter(s -> PLAYER_REF.equals(s.getPlayerRef()))
				.forEach(this.sessions::delete);
	}

	/**
	 * <b>둘이 같은 지점에서 잘린다.</b>
	 *
	 * <p>R14.4 의 전부가 이것이다 — 되돌린 뒤 살아 있는 스냅샷과 요약이 <b>같은 턴 이하</b>를
	 * 가리켜야 한다.
	 */
	@Test
	void R14_4_the_snapshot_and_the_summary_fold_together() {
		UUID sessionId = givenSessionAt(5);

		this.rollback.rollbackTo(sessionId, 3);

		assertThat(this.snapshots.findFirstBySessionIdAndDeletedAtIsNullOrderByTurnNoDesc(sessionId))
				.get().extracting(GameStateSnapshot::getTurnNo).isEqualTo(3);
		assertThat(this.summaries
				.findFirstBySessionIdAndDeletedAtIsNullOrderByUptoTurnNoDescCreatedAtDesc(sessionId))
				.get().extracting(StorySummary::getUptoTurnNo).isEqualTo(3);
	}

	/** <b>요약만 남는 상태를 만들 수 없다.</b> 접히지 않은 요약이 스냅샷보다 앞서지 않는다. */
	@Test
	void R14_4_no_summary_survives_past_the_surviving_snapshot() {
		UUID sessionId = givenSessionAt(5);

		this.rollback.rollbackTo(sessionId, 2);

		int liveSnapshot = this.snapshots
				.findFirstBySessionIdAndDeletedAtIsNullOrderByTurnNoDesc(sessionId)
				.map(GameStateSnapshot::getTurnNo).orElse(0);
		assertThat(this.summaries.findAll().stream()
				.filter(s -> sessionId.equals(s.getSessionId()) && s.getDeletedAt() == null)
				.map(StorySummary::getUptoTurnNo))
				.allSatisfy(upto -> assertThat(upto).isLessThanOrEqualTo(liveSnapshot));
	}

	/** <b>지우지 않는다</b> (I-5). 접힌 행은 {@code deleted_at} 만 찍힌 채 남는다. */
	@Test
	void I5_folded_rows_are_kept() {
		UUID sessionId = givenSessionAt(5);

		this.rollback.rollbackTo(sessionId, 3);

		assertThat(this.turns.findAll().stream().filter(t -> sessionId.equals(t.getSessionId())))
				.hasSize(5);
		assertThat(this.turns.findAll().stream()
				.filter(t -> sessionId.equals(t.getSessionId()) && t.getDeletedAt() != null))
				.hasSize(2);
	}

	/** 세션의 진행도 함께 되돌아간다 (I-6). 되돌린 지점이 곧 현재 턴이다. */
	@Test
	void I6_the_session_turn_number_follows_the_rollback() {
		UUID sessionId = givenSessionAt(5);

		var result = this.rollback.rollbackTo(sessionId, 3);

		assertThat(result.turnNo()).isEqualTo(3);
		assertThat(result.foldedTurns()).isEqualTo(2);
		assertThat(this.sessions.findById(sessionId)).get()
				.extracting(PlaySession::getTurnNo).isEqualTo(3);
	}

	/**
	 * <b>끝난 세션도 되돌릴 수 있고, 끝났다는 사실이 함께 지워진다.</b>
	 *
	 * <p>남겨 두면 마이그레이션의 CHECK 가 거절하고, 통과하더라도 <b>끝난 세션이 계속
	 * 진행되는</b> 상태가 된다.
	 */
	@Test
	void R14_4_rolling_back_a_completed_session_clears_the_ending() {
		UUID sessionId = givenSessionAt(5);
		PlaySession session = this.sessions.findById(sessionId).orElseThrow();
		session.complete(UUID.randomUUID(), Instant.now());
		this.sessions.saveAndFlush(session);

		this.rollback.rollbackTo(sessionId, 3);

		assertThat(this.sessions.findById(sessionId)).get().satisfies(rolled -> {
			assertThat(rolled.getStatus()).isEqualTo(SessionStatus.ACTIVE);
			assertThat(rolled.getCompletedAt()).isNull();
			assertThat(rolled.getCurrentEndingId()).isNull();
		});
	}

	/** 앞으로는 되돌리지 못한다 — "되돌리기"가 진행이 되면 안 된다. */
	@Test
	void I6_rolling_forward_is_refused() {
		UUID sessionId = givenSessionAt(5);

		assertThatThrownBy(() -> this.rollback.rollbackTo(sessionId, 5))
				.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> this.rollback.rollbackTo(sessionId, 9))
				.isInstanceOf(ApiException.class);
	}

	/** 0 까지 되돌리면 아무 턴도 남지 않고, 챕터는 1 로 간다. */
	@Test
	void R14_4_rolling_back_to_zero_leaves_nothing_and_resets_the_chapter() {
		UUID sessionId = givenSessionAt(5);

		var result = this.rollback.rollbackTo(sessionId, 0);

		assertThat(result.turnNo()).isZero();
		assertThat(result.chapterNo()).isEqualTo(1);
		assertThat(this.turns.findBySessionIdAndDeletedAtIsNullOrderByTurnNoDesc(sessionId,
				org.springframework.data.domain.Limit.of(10))).isEmpty();
	}

	/** 두 번 되돌려도 처음 접힌 기록이 덮이지 않는다. */
	@Test
	void R14_4_a_second_rollback_keeps_the_first_fold_time() {
		UUID sessionId = givenSessionAt(5);
		this.rollback.rollbackTo(sessionId, 3);
		Instant first = this.turns.findAll().stream()
				.filter(t -> sessionId.equals(t.getSessionId()) && t.getTurnNo() == 5)
				.findFirst().orElseThrow().getDeletedAt();

		this.rollback.rollbackTo(sessionId, 1);

		assertThat(this.turns.findAll().stream()
				.filter(t -> sessionId.equals(t.getSessionId()) && t.getTurnNo() == 5)
				.findFirst().orElseThrow().getDeletedAt()).isEqualTo(first);
	}

	/** 턴 · 스냅샷 · 요약이 1..n 으로 가지런히 쌓인 세션. */
	private UUID givenSessionAt(int turnNo) {
		Instant now = Instant.now();
		PlaySession session = this.sessions.save(PlaySession.start(PLAYER_REF, STORY_ID, VERSION_ID,
				"fixed", "scenario", false, now));
		UUID sessionId = session.getId();

		for (int no = 1; no <= turnNo; no++) {
			session.recordTurn(no, 1, now);
			this.turns.save(Turn.create(new Turn.TurnDraft(sessionId, no, 1, "[\"본문\"]", "[]", null,
					false, false, null, SafetyVerdict.PASS, true), now));
			this.snapshots.save(GameStateSnapshot.capture(sessionId, no, "{\"turn\":%d}".formatted(no), now));
			this.summaries.save(StorySummary.of(sessionId, no, "요약 " + no, 10, now));
		}
		this.sessions.saveAndFlush(session);
		return sessionId;
	}
}

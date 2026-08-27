package com.neowadaeum.play.debug;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.play.domain.GameStateSnapshot;
import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.domain.StorySummary;
import com.neowadaeum.play.domain.Turn;
import com.neowadaeum.play.repository.GameStateSnapshotRepository;
import com.neowadaeum.play.repository.PlaySessionRepository;
import com.neowadaeum.play.repository.StorySummaryRepository;
import com.neowadaeum.play.repository.TurnRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 되돌리기 (R14.4, B-42).
 *
 * <p><b>스냅샷과 요약을 함께 되돌린다.</b> 둘 중 하나만 접히면 <b>상태와 이야기가 다른 지점을
 * 가리킨 채</b> 다음 턴이 만들어진다 — 요약은 12턴까지의 이야기를 담았는데 게임 상태는 8턴의
 * 것이면, AI 는 있지도 않았던 전개를 전제로 글을 쓴다.
 *
 * <p><b>한 트랜잭션 안에서 한다.</b> 나누면 그 사이에 실패한 되돌리기가 정확히 그 어긋난
 * 상태를 남긴다. "요약만 남는 상태를 재현할 수 없다"는 것이 R14.4 의 검증 조건이며, 그것을
 * 보장하는 것은 순서가 아니라 <b>경계</b>다.
 *
 * <p><b>지우지 않는다.</b> 되돌린 턴·스냅샷·요약은 {@code deleted_at} 만 찍힌다 (I-5). 지나간
 * 플레이는 기록이고, 무엇을 어디까지 되돌렸는지는 그 행이 남아 있어야 알 수 있다.
 */
@Service
public class SessionRollbackFacade {

	private final PlaySessionRepository sessions;

	private final TurnRepository turns;

	private final GameStateSnapshotRepository snapshots;

	private final StorySummaryRepository summaries;

	private final Clock clock;

	public SessionRollbackFacade(PlaySessionRepository sessions, TurnRepository turns,
			GameStateSnapshotRepository snapshots, StorySummaryRepository summaries, Clock clock) {
		this.sessions = sessions;
		this.turns = turns;
		this.snapshots = snapshots;
		this.summaries = summaries;
		this.clock = clock;
	}

	/**
	 * {@code toTurnNo} 까지 되돌린다. 그 턴은 <b>남는다</b> — 되돌린 뒤 화면에 떠 있을 턴이다.
	 *
	 * @throws ApiException {@code NOT_FOUND} — 없는 세션
	 * @throws ApiException {@code VALIDATION_ERROR} — 되돌릴 수 없는 지점. 현재보다 뒤이거나,
	 *     그 자리에 살아 있는 턴이 없다. <b>0 은 허용한다</b> — 첫 턴까지 지우는 되돌리기다
	 */
	@Transactional("playTransactionManager")
	public RollbackResult rollbackTo(UUID sessionId, int toTurnNo) {
		PlaySession session = this.sessions.findById(sessionId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (toTurnNo < 0 || toTurnNo >= session.getTurnNo() || session.getDeletedAt() != null) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
		Instant now = Instant.now(this.clock);

		List<Turn> foldedTurns = this.turns
				.findBySessionIdAndTurnNoGreaterThanAndDeletedAtIsNull(sessionId, toTurnNo);
		List<GameStateSnapshot> foldedSnapshots = this.snapshots
				.findBySessionIdAndTurnNoGreaterThanAndDeletedAtIsNull(sessionId, toTurnNo);
		List<StorySummary> foldedSummaries = this.summaries
				.findBySessionIdAndUptoTurnNoGreaterThanAndDeletedAtIsNull(sessionId, toTurnNo);

		foldedTurns.forEach(turn -> turn.softDelete(now));
		foldedSnapshots.forEach(snapshot -> snapshot.softDelete(now));
		foldedSummaries.forEach(summary -> summary.softDelete(now));

		session.rewindTo(toTurnNo, chapterAt(sessionId, toTurnNo), now);

		return new RollbackResult(toTurnNo, session.getChapterNo(), foldedTurns.size(),
				foldedSnapshots.size(), foldedSummaries.size());
	}

	/**
	 * 되돌린 지점의 챕터.
	 *
	 * <p>턴 0 으로 되돌리면 아무 턴도 남지 않는다 — 그때는 <b>1장</b>이다. 세션의 현재 챕터를
	 * 그대로 두면 <b>있지도 않은 진행의 챕터</b>가 남는다.
	 */
	private int chapterAt(UUID sessionId, int toTurnNo) {
		if (toTurnNo == 0) {
			return 1;
		}
		return this.turns.findBySessionIdAndTurnNoAndDeletedAtIsNull(sessionId, toTurnNo)
				.map(Turn::getChapterNo)
				.orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR));
	}

	/**
	 * 무엇을 어디까지 되돌렸나.
	 *
	 * <p><b>접은 수를 함께 돌려준다</b> — 감사에 남길 값이며 (R14.5), "되돌렸다"만으로는
	 * 사후에 무엇이 일어났는지 알 수 없다.
	 */
	public record RollbackResult(int turnNo, int chapterNo, int foldedTurns, int foldedSnapshots,
			int foldedSummaries) {
	}
}

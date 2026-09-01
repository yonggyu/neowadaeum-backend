package com.neowadaeum.play.debug;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.domain.Turn;
import com.neowadaeum.play.repository.GameStateSnapshotRepository;
import com.neowadaeum.play.repository.PlaySessionRepository;
import com.neowadaeum.play.repository.StorySummaryRepository;
import com.neowadaeum.play.repository.TurnRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 세션의 현재 상태를 관리자 화면에 내준다 (§14 Debug).
 *
 * <p><b>읽기만 한다.</b> 이 파사드로 상태가 바뀌는 경로는 없다 — 재생성·롤백은 B-42 이며 그것은
 * <b>다른 문</b>이어야 한다. 보는 것과 고치는 것을 한 자리에 두면 실수로 고치게 된다.
 *
 * <p><b>소유자를 보지 않는다.</b> 관리자 화면은 남의 세션을 보는 것이 목적이다 — 대신 그 문 앞에
 * S-4 의 세 조건이 서 있고, 부르는 쪽이 그것을 통과시킨다.
 */
@Service
public class SessionDebugFacade {

	/** 화면 하나에 들어갈 만큼. 더 필요하면 기록 화면(§13.6)이 따로 있다. */
	private static final int RECENT_TURNS = 10;

	private final PlaySessionRepository sessions;

	private final TurnRepository turns;

	private final GameStateSnapshotRepository snapshots;

	private final StorySummaryRepository summaries;

	public SessionDebugFacade(PlaySessionRepository sessions, TurnRepository turns,
			GameStateSnapshotRepository snapshots, StorySummaryRepository summaries) {
		this.sessions = sessions;
		this.turns = turns;
		this.snapshots = snapshots;
		this.summaries = summaries;
	}

	/**
	 * @throws ApiException {@code NOT_FOUND} — 없는 세션. <b>지운 세션은 보인다</b> — 무슨 일이
	 *     있었는지 보는 것이 디버그의 목적이고, 지워졌다는 사실도 그 일부다
	 */
	@Transactional(value = "playTransactionManager", readOnly = true)
	public SessionDebugView debug(UUID sessionId) {
		PlaySession session = this.sessions.findById(sessionId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));

		List<Turn> recent = this.turns.findBySessionIdAndDeletedAtIsNullOrderByTurnNoDesc(sessionId,
				Limit.of(RECENT_TURNS));
		List<SessionDebugView.TurnView> turnViews = new ArrayList<>(recent.size());
		for (Turn turn : recent) {
			turnViews.add(turnViewOf(turn));
		}

		String gameState = this.snapshots
				.findFirstBySessionIdAndDeletedAtIsNullOrderByTurnNoDesc(sessionId)
				.map(com.neowadaeum.play.domain.GameStateSnapshot::getState).orElse(null);
		var summary = this.summaries
				.findFirstBySessionIdAndDeletedAtIsNullOrderByUptoTurnNoDescCreatedAtDesc(sessionId);

		return new SessionDebugView(session.getId(), session.getStoryId(), session.getStoryVersionId(),
				session.getStatus().name().toLowerCase(java.util.Locale.ROOT), session.getProviderId(),
				session.getModelId(), session.getTurnNo(), session.getChapterNo(),
				session.isTestSession(), gameState,
				summary.map(com.neowadaeum.play.domain.StorySummary::getSummaryText).orElse(null),
				summary.map(com.neowadaeum.play.domain.StorySummary::getUptoTurnNo).orElse(null),
				turnViews, session.getCreatedAt(), session.getUpdatedAt());
	}

	private static SessionDebugView.TurnView turnViewOf(Turn turn) {
		return new SessionDebugView.TurnView(turn.getTurnNo(), turn.getChapterNo(),
				turn.getSpeakerName(), turn.getParagraphs(), turn.getChoices(),
				turn.getChosenChoiceId(),
				(turn.getSafetyVerdict() != null) ? turn.getSafetyVerdict().name() : null,
				turn.isAdminFreeInput(), turn.isEnding(), turn.getCreatedAt());
	}
}

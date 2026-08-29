package com.neowadaeum.play.preview;

import com.neowadaeum.common.spi.PreviewSessionPurge;
import com.neowadaeum.play.repository.GameStateSnapshotRepository;
import com.neowadaeum.play.repository.PlaySessionRepository;
import com.neowadaeum.play.repository.StorySummaryRepository;
import com.neowadaeum.play.repository.TurnRepository;
import java.util.Collection;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 미리보기 작품과 함께 사라지는 세션 (§13-37, B-61).
 *
 * <p><b>작품과 한 벌이다.</b> 미리보기 세션은 그 작품 위에서만 의미가 있고 3턴에서 끝난다
 * (R8.13, {@link PreviewSessionStarter}) — 작품이 사라지면 그 세션은 <b>읽을 수 없는 기록</b>이
 * 된다.
 *
 * <p><b>매달린 것부터 지운다.</b> 턴 · 스냅샷 · 요약이 모두 세션을 FK 로 가리킨다.
 */
@Service
public class PreviewSessionPurgeService implements PreviewSessionPurge {

	private final PlaySessionRepository sessions;

	private final TurnRepository turns;

	private final GameStateSnapshotRepository snapshots;

	private final StorySummaryRepository summaries;

	public PreviewSessionPurgeService(PlaySessionRepository sessions, TurnRepository turns,
			GameStateSnapshotRepository snapshots, StorySummaryRepository summaries) {
		this.sessions = sessions;
		this.turns = turns;
		this.snapshots = snapshots;
		this.summaries = summaries;
	}

	@Override
	@Transactional("playTransactionManager")
	public int purgeByStories(Collection<UUID> storyIds) {
		if (storyIds.isEmpty()) {
			return 0;
		}
		this.summaries.deleteByStoryIds(storyIds);
		this.snapshots.deleteByStoryIds(storyIds);
		this.turns.deleteByStoryIds(storyIds);
		return this.sessions.deleteByStoryIds(storyIds);
	}
}

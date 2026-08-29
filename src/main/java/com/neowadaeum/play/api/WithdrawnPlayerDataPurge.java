package com.neowadaeum.play.api;

import com.neowadaeum.common.spi.PlayerDataPurge;
import com.neowadaeum.play.repository.GameStateSnapshotRepository;
import com.neowadaeum.play.repository.PlaySessionRepository;
import com.neowadaeum.play.repository.StorySummaryRepository;
import com.neowadaeum.play.repository.TurnRepository;
import com.neowadaeum.play.stats.PurgedSessionTally;
import java.util.Collection;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 탈퇴 회원의 플레이 기록 파기 (R12.4, B-61).
 *
 * <p><b>만료와 다르다.</b> 무활동 만료는 이어갈 수만 없게 만들고 기록을 남긴다
 * ({@link IdleSessionExpiry}) — 사용자가 <b>자기가 어디까지 갔었는지</b>를 잃지 않기 위해서다.
 * 탈퇴에는 그 사용자가 없다.
 *
 * <p><b>매달린 것부터 지운다.</b> 턴 · 스냅샷 · 요약이 모두 세션을 FK 로 가리키므로 순서를
 * 뒤집으면 제약에 걸린다 — 그리고 그 실패는 <b>지워야 할 것이 남는 방식</b>으로 끝난다.
 *
 * <p><b>한 트랜잭션이다.</b> 중간에 끊기면 턴 없는 세션이나 세션 없는 턴이 남고, 그것은 어느
 * 쪽으로도 복구할 수 없는 상태다.
 *
 * <p><b>지우기 전에 도달 집계에 더한다</b> (§13-44, 이슈 #228). 도달률은 매 회차 살아 있는
 * 세션을 전량 재계산하므로(B-39), 그냥 지우면 <b>탈퇴 한 건이 그 사람이 완주했던 모든 작품의
 * 도달률을 줄인다</b> — 작품에 아무 변화가 없는데 숫자가 바뀐다. 옮겨 두는 것은 개인과 다시
 * 이을 수 없는 합계뿐이며 ({@link PurgedSessionTally}), 원본 기록은 그대로 파기된다.
 *
 * <p><b>순서를 뒤집을 수 없다.</b> 지운 뒤에는 무엇을 더해야 할지 알 수 없다.
 */
@Service
public class WithdrawnPlayerDataPurge implements PlayerDataPurge {

	private final PlaySessionRepository sessions;

	private final TurnRepository turns;

	private final GameStateSnapshotRepository snapshots;

	private final StorySummaryRepository summaries;

	private final PurgedSessionTally purgedTally;

	public WithdrawnPlayerDataPurge(PlaySessionRepository sessions, TurnRepository turns,
			GameStateSnapshotRepository snapshots, StorySummaryRepository summaries,
			PurgedSessionTally purgedTally) {
		this.sessions = sessions;
		this.turns = turns;
		this.snapshots = snapshots;
		this.summaries = summaries;
		this.purgedTally = purgedTally;
	}

	@Override
	@Transactional("playTransactionManager")
	public int purge(Collection<UUID> playerRefs) {
		if (playerRefs.isEmpty()) {
			return 0;
		}
		this.purgedTally.archive(playerRefs);
		this.summaries.deleteByPlayerRefs(playerRefs);
		this.snapshots.deleteByPlayerRefs(playerRefs);
		this.turns.deleteByPlayerRefs(playerRefs);
		return this.sessions.deleteByPlayerRefs(playerRefs);
	}
}

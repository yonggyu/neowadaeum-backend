package com.neowadaeum.play.preview;

import com.neowadaeum.common.spi.PreviewTurnsQuery;
import com.neowadaeum.play.domain.Turn;
import com.neowadaeum.play.repository.TurnRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 미리보기 세션의 턴을 검수 쪽에 내준다 (§13-68, #332).
 *
 * <p><b>{@code authoring} 은 {@code play} 를 알지 못한다.</b> 그 경계를 지키면서 턴을 읽는 길이
 * {@link PreviewTurnsQuery} 이며, {@link PreviewSessionStarter} 가 세션을 여는 데 쓴 것과 같은
 * 경계의 반대 방향이다.
 *
 * <p><b>세션이 없으면 빈 목록이다.</b> 파기되었거나(§13-37) 미리보기를 돌린 적이 없다 — 어느
 * 쪽이든 <b>없다는 것이 사실</b>이고, 404 로 답하면 검수 상세 전체가 열리지 않는다.
 */
@Service
public class PreviewTurns implements PreviewTurnsQuery {

	private final TurnRepository turns;

	public PreviewTurns(TurnRepository turns) {
		this.turns = turns;
	}

	@Override
	@Transactional(value = "playTransactionManager", readOnly = true)
	public List<PreviewTurn> findBySession(UUID sessionId) {
		if (sessionId == null) {
			return List.of();
		}
		List<Turn> rows = this.turns.findBySessionIdAndDeletedAtIsNullOrderByTurnNoAsc(sessionId);
		List<PreviewTurn> views = new ArrayList<>(rows.size());
		for (Turn turn : rows) {
			views.add(new PreviewTurn(turn.getTurnNo(), turn.getChapterNo(), turn.getSpeakerName(),
					turn.getParagraphs(), turn.getChoices(), turn.getCreatedAt()));
		}
		return views;
	}

}

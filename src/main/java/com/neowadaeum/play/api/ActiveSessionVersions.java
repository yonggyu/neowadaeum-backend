package com.neowadaeum.play.api;

import com.neowadaeum.common.spi.PinnedStoryVersions;
import com.neowadaeum.play.domain.SessionStatus;
import com.neowadaeum.play.repository.PlaySessionRepository;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 진행 중 세션이 고정한 버전 — {@link PinnedStoryVersions} 의 play 쪽 구현 (§13-80, #372).
 *
 * <p><b>여기가 아니면 물을 곳이 없다.</b> 세션은 {@code play} 스키마에 있고 작품은
 * {@code catalog} 스키마에 있어 JOIN 이 금지돼 있다 (§5.3) — 참조는 애플리케이션 레벨에서만
 * 하며, ADR-0003 이 정한 그 자리가 {@code common/spi} 다.
 *
 * <p><b>id 만 돌려준다.</b> 세션이 무엇인지·누구 것인지는 검수가 알 일이 아니고, 그것을 실어
 * 보내면 {@code playerRef} 가 검수 경로로 흘러간다 (I-3 과 같은 판단).
 */
@Service
public class ActiveSessionVersions implements PinnedStoryVersions {

	private final PlaySessionRepository sessions;

	public ActiveSessionVersions(PlaySessionRepository sessions) {
		this.sessions = sessions;
	}

	/**
	 * <p><b>빈 목록을 그대로 넘기지 않는다</b> — {@code IN ()} 은 스토어마다 다르게 군다
	 * ({@code playCountsByStoryIds} 와 같은 자리).
	 */
	@Override
	@Transactional(value = "playTransactionManager", readOnly = true)
	public Set<UUID> pinnedVersionsOf(Collection<UUID> storyIds) {
		if (storyIds.isEmpty()) {
			return Set.of();
		}
		return new LinkedHashSet<>(
				this.sessions.findPinnedVersionIds(storyIds, SessionStatus.ACTIVE));
	}
}

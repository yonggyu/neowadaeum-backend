package com.neowadaeum.play.debug;

import java.util.List;

/**
 * 세션 목록 한 쪽 (#339).
 *
 * <p>커서는 정렬 키 그대로다 — {@code (updatedAt, id)}. 시각만으로는 같은 시각의 세션들이
 * 쪽 경계에서 <b>중복되거나 사라진다.</b>
 */
public record SessionListPage(List<SessionListView> sessions, String nextCursor, boolean hasMore) {

	public SessionListPage {
		sessions = List.copyOf(sessions == null ? List.of() : sessions);
	}
}

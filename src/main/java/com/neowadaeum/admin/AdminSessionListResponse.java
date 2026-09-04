package com.neowadaeum.admin;

import com.neowadaeum.play.debug.SessionListView;
import java.util.List;

/**
 * 관리자 세션 목록 응답 (§14, #339).
 *
 * <p>봉투는 다른 목록과 같다 — {@code items} · {@code nextCursor} · {@code hasMore}.
 */
public record AdminSessionListResponse(List<SessionListView> items, String nextCursor,
		boolean hasMore) {

	public AdminSessionListResponse {
		items = List.copyOf(items == null ? List.of() : items);
	}
}

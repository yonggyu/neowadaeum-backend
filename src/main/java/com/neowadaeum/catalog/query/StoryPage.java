package com.neowadaeum.catalog.query;

import java.util.List;

/**
 * 섹션 한 쪽 (§13.2).
 *
 * <p><b>{@code hasMore} 와 {@code nextCursor} 는 같은 사실의 두 표현이다.</b> 커서가 있으면 더
 * 있는 것이고, 없으면 끝이다 — 둘을 따로 계산하면 언젠가 어긋난다.
 *
 * <p>커서는 <b>불투명 문자열</b>이다. 클라이언트가 해석하거나 만들어 보내는 것을 전제하지 않는다.
 */
public record StoryPage(List<StoryCardView> stories, String nextCursor) {

	public StoryPage {
		stories = List.copyOf(stories == null ? List.of() : stories);
	}

	public boolean hasMore() {
		return this.nextCursor != null;
	}
}

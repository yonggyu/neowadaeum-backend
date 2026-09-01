package com.neowadaeum.catalog.query;

import java.util.List;

/** 내가 만든 작품의 한 쪽 (§13.7). 커서 규칙은 {@link StoryPage} 와 같다. */
public record MyStoryPage(List<MyStoryView> stories, String nextCursor) {

	public MyStoryPage {
		stories = List.copyOf(stories == null ? List.of() : stories);
	}

	public boolean hasMore() {
		return this.nextCursor != null;
	}
}

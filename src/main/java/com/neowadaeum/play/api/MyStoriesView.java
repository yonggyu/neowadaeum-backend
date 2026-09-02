package com.neowadaeum.play.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 내 것들 (§13.7, 화면 2.4).
 *
 * <p>두 탭이 같은 봉투를 쓴다 — {@code items} · {@code nextCursor} · {@code hasMore} 다.
 * 계약이 {@code MySessionsResponse} 와 {@code MyStoriesResponse} 로 나눠 두었으므로 타입도
 * 나누되, <b>모양은 같게</b> 둔다.
 */
public final class MyStoriesView {

	private MyStoriesView() {
	}

	/**
	 * 진행 중 / 완료 탭 (§13.7).
	 *
	 * @param noticeText AI 사전 고지 문구. <b>코드에 없다</b> — {@code service_config} 에서 온다
	 *                   (R11.1). 이 화면의 Footer 도 문구를 상시 표시하므로, 없으면 이 화면이
	 *                   <b>랜딩을 따로 부르게 된다</b> (#281)
	 */
	public record Sessions(List<SessionItem> items, String nextCursor, boolean hasMore,
			String noticeText) {

		public Sessions {
			items = List.copyOf(items == null ? List.of() : items);
		}
	}

	/**
	 * 세션 한 줄.
	 *
	 * <p><b>진행률을 백분율로 주지 않는다</b> (R13.2) — 라이브러리와 같은 이유다.
	 *
	 * @param status {@code active} 또는 {@code completed}. <b>{@code in_progress} 는 존재하지 않는
	 *               상태였다</b> (§13-6)
	 */
	public record SessionItem(UUID sessionId, UUID storyId, String title, String coverImage, String status,
			int chapterNo, int totalChapters, Instant updatedAt) {
	}

	/**
	 * 내가 만든 작품 탭 (R13.4).
	 *
	 * @param noticeText AI 사전 고지 문구. {@link Sessions#noticeText()} 와 같은 이유다 (#281)
	 */
	public record Stories(List<StoryItem> items, String nextCursor, boolean hasMore,
			String noticeText) {

		public Stories {
			items = List.copyOf(items == null ? List.of() : items);
		}
	}

	/**
	 * 작품 한 줄.
	 *
	 * @param rejectReasons <b>카테고리만</b> (R8.7)
	 * @param playCount     플레이된 횟수. play 스토어에서 온다 (§5.3)
	 */
	public record StoryItem(UUID storyId, String title, String coverImage, String visibility,
			String reviewStatus, List<String> rejectReasons, long playCount, Instant updatedAt) {

		public StoryItem {
			rejectReasons = List.copyOf(rejectReasons == null ? List.of() : rejectReasons);
		}
	}
}

package com.neowadaeum.play.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Resume 요약 (§13.4, 화면 2e).
 *
 * <p><b>{@code sessionState} 가 이 응답의 핵심이다.</b> 나머지 값은 화면을 그리기 위한 것이고,
 * <b>이어하기가 가능한가</b>는 이 하나가 답한다 (§4.7).
 *
 * <p>진행률을 백분율로 주지 않는다 (R13.2, R7.5) — 라이브러리와 같은 이유다.
 *
 * @param lastSceneVisual 마지막 장면 이미지. <b>아직 발행하지 않는다</b>(P3) — 계약이 요구하는
 *                        필드이므로 자리는 두고 값은 {@code null} 이다
 * @param canViewHistory  기록을 볼 수 있는가. 조회 자체는 B-35 다
 */
public record ResumeView(UUID sessionId, UUID storyId, String title, int chapterNo, String chapterTitle,
		int totalChapters, int turnNo, Instant updatedAt, String lastSceneVisual, String lastSceneSummary,
		String lastChoiceText, String sessionState, boolean canViewHistory) {

	/**
	 * 이어하기 가능 여부 (§13.4 의 {@code SessionState}).
	 *
	 * <p><b>{@code valid} 만 이어갈 수 있다.</b> 나머지 넷은 각자 다른 안내를 요구하므로 하나로
	 * 합치지 않는다 — 만료와 정지는 사용자가 할 수 있는 일이 다르다.
	 */
	public enum State {

		/** 이어갈 수 있다. */
		VALID,

		/** 90일 무활동 (§4.7). */
		EXPIRED,

		/** 사용자가 지웠다 (§13.4). */
		DELETED,

		/** 세션이 고정한 버전이 현재 버전과 다르다 (R2.1). */
		VERSION_CHANGED,

		/** UGC 정지 (R8.10, R13.3). 읽기 전용이다. */
		STORY_SUSPENDED;

		/** 응답 표기는 소문자다 — 계약의 enum 이 그렇다. */
		String wire() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}
	}
}

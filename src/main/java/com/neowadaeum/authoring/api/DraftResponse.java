package com.neowadaeum.authoring.api;

import com.neowadaeum.authoring.draft.StoryDraft;
import java.time.Instant;
import java.util.UUID;

/**
 * 원고 (§13.8).
 *
 * <p><b>작성자에게만 나간다</b> (I-8). {@code payload} 는 검수 대상 원문이며 (R2.4), 이 응답이
 * 그것이 나가는 유일한 자리다.
 *
 * <p><b>{@code authorRef} 를 담지 않는다</b> (I-3) — 받는 사람이 곧 작성자다.
 *
 * @param findings 검수 결과 (R8.2). precheck 전에는 빈 배열이다 — 판정은 B-50 이 채운다
 */
public record DraftResponse(UUID draftId, UUID storyId, int step, String payload, String safetyState,
		String findings, Instant updatedAt) {

	static DraftResponse of(StoryDraft draft) {
		return new DraftResponse(draft.getId(), draft.getStoryId(), draft.getStep(),
				draft.getPayload(), draft.getSafetyState().columnValue(), draft.getSafetyFindings(),
				draft.getUpdatedAt());
	}
}

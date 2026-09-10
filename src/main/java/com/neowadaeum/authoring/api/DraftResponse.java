package com.neowadaeum.authoring.api;

import com.neowadaeum.authoring.draft.StoryDraft;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 원고 (§13.8).
 *
 * <p><b>작성자에게만 나간다</b> (I-8). {@code payload} 는 검수 대상 원문이며 (R2.4), 이 응답이
 * 그것이 나가는 유일한 자리다.
 *
 * <p><b>{@code authorRef} 를 담지 않는다</b> (I-3) — 받는 사람이 곧 작성자다.
 *
 * <p><b>{@code payload} 와 {@code findings} 를 {@code String} 으로 담지 않는다</b> (이슈 #465).
 * 담으면 Jackson 이 JSON 원문을 한 번 더 escape 해 <b>객체 대신 문자열</b>이 나간다 — 갓 만든
 * 원고의 {@code payload} 가 {@code {}} 가 아니라 두 글자짜리 {@code "{}"} 로 보이던 것이 그것이다.
 * 컬럼은 {@code jsonb} 이고 계약도 객체·배열이며, 어긋난 곳은 이 자리 하나였다.
 *
 * @param findings 검수 결과 (R8.2). precheck 전에는 빈 배열이다 — 판정은 B-50 이 채운다
 */
public record DraftResponse(UUID draftId, UUID storyId, int step, JsonNode payload, String safetyState,
		JsonNode findings, Instant updatedAt) {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	static DraftResponse of(StoryDraft draft) {
		return new DraftResponse(draft.getId(), draft.getStoryId(), draft.getStep(),
				JSON.readTree(draft.getPayload()), draft.getSafetyState().columnValue(),
				JSON.readTree(draft.getSafetyFindings()), draft.getUpdatedAt());
	}
}

package com.neowadaeum.authoring.api;

import com.neowadaeum.authoring.review.StoryVisibilityService;
import com.neowadaeum.authoring.review.SubmissionService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 검수 상태 (§13.8).
 *
 * <p><b>비율도 임계값도 담지 않는다</b> (§13-12, S-11) — 값을 알면 그 아래로 관리할 수 있다.
 *
 * <p><b>{@code rejectReasons} 는 카테고리만이다</b> (R8.7). 어떤 항목에 걸렸는지를 알려 주면
 * 우회 학습을 돕는다.
 *
 * @param storyId 반려됐으면 비어 있다 — 작품이 만들어지지 않았다
 */
public record ReviewStatusResponse(UUID storyId, String reviewStatus, String visibility,
		List<String> rejectReasons, Instant updatedAt) {

	static ReviewStatusResponse of(SubmissionService.SubmissionOutcome outcome, Instant now) {
		return new ReviewStatusResponse(outcome.storyId(), outcome.reviewStatus().columnValue(),
				outcome.visibility().columnValue(), outcome.rejectReasons(), now);
	}

	/**
	 * 가시성 변경 결과 (#245).
	 *
	 * <p><b>{@code rejectReasons} 는 비어 있다.</b> 이 요청은 판정이 아니라 요청이며, 사유가
	 * 생기는 자리는 검수다 (R8.7).
	 */
	static ReviewStatusResponse of(StoryVisibilityService.VisibilityOutcome outcome, Instant now) {
		return new ReviewStatusResponse(outcome.storyId(), outcome.reviewStatus().columnValue(),
				outcome.visibility().columnValue(), List.of(), now);
	}
}

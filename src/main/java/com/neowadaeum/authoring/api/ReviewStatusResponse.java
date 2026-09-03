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
 * <p><b>{@code updatedAt} 은 신청 시각도 승인 시각도 아니다</b> (#290). 그것은 <b>지금 이
 * 응답을 만든 시각</b>이며, 화면이 <b>"2월 21일 신청 · 보통 1~3일"</b> 이라고 적으려면
 * <b>언제부터 세는지</b>가 따로 있어야 한다. 두 시각은 검수 이력에서 파생한다 (§13-57).
 *
 * @param storyId 반려됐으면 비어 있다 — 작품이 만들어지지 않았다
 * @param submittedAt 지금 회차의 검수를 요청한 시각. 요청한 적이 없으면 {@code null}
 * @param reviewedAt 그 회차에서 사람이 마지막으로 판정한 시각. 아직 없으면 {@code null}
 */
public record ReviewStatusResponse(UUID storyId, String reviewStatus, String visibility,
		List<String> rejectReasons, Instant updatedAt, Instant submittedAt, Instant reviewedAt) {

	static ReviewStatusResponse of(SubmissionService.SubmissionOutcome outcome, Instant now) {
		return new ReviewStatusResponse(outcome.storyId(), outcome.reviewStatus().columnValue(),
				outcome.visibility().columnValue(), outcome.rejectReasons(), now,
				outcome.times().submittedAt(), outcome.times().reviewedAt());
	}

	/**
	 * 가시성 변경 결과 (#245).
	 *
	 * <p><b>{@code rejectReasons} 는 비어 있다.</b> 이 요청은 판정이 아니라 요청이며, 사유가
	 * 생기는 자리는 검수다 (R8.7).
	 */
	static ReviewStatusResponse of(StoryVisibilityService.VisibilityOutcome outcome, Instant now) {
		return new ReviewStatusResponse(outcome.storyId(), outcome.reviewStatus().columnValue(),
				outcome.visibility().columnValue(), List.of(), now, outcome.times().submittedAt(),
				outcome.times().reviewedAt());
	}
}

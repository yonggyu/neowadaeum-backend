package com.neowadaeum.admin;

import com.neowadaeum.authoring.review.ReviewQueueService;
import java.time.Instant;
import java.util.UUID;

/**
 * 검수 큐의 한 줄 (§14, B-55).
 *
 * <p><b>작성자를 담지 않는다.</b> 검수는 <b>무엇이 쓰였는가</b>를 보는 일이고, 누가 썼는지가
 * 함께 오면 그것이 판정에 섞인다 — 그리고 {@code player_ref} 는 응답에 나가지 않는다.
 *
 * <p><b>원고 본문도 담지 않는다.</b> 큐는 무엇을 볼 차례인지를 답하는 자리이며, 원문 열람은
 * 감사가 걸린 다른 문이다 (S-5).
 *
 * <p><b>왜 큐에 있는지는 담는다</b> (B-57). 제출을 기다리는 것({@code in_review})과 신고로
 * 내려간 것({@code suspended})은 검수자가 <b>다르게 봐야 하는 일</b>이다 — 하나는 아직 아무도
 * 못 본 작품이고, 다른 하나는 이미 사람들이 본 작품이다.
 *
 * @param queuedAt 언제부터 기다렸는가. 마지막 판정 시각이다
 */
public record ReviewQueueItemResponse(UUID storyId, String title, String reviewStatus,
		Instant queuedAt) {

	static ReviewQueueItemResponse of(ReviewQueueService.QueueItem item) {
		return new ReviewQueueItemResponse(item.storyId(), item.title(),
				item.reviewStatus().columnValue(), item.queuedAt());
	}
}

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
 * @param queuedAt 언제부터 기다렸는가. 자동 검수가 통과시킨 시각이다
 */
public record ReviewQueueItemResponse(UUID storyId, String title, Instant queuedAt) {

	static ReviewQueueItemResponse of(ReviewQueueService.QueueItem item) {
		return new ReviewQueueItemResponse(item.storyId(), item.title(), item.queuedAt());
	}
}

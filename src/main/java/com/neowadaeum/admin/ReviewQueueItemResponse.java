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
 * <p><b>작성자가 이의를 제기했는지는 담는다</b> (#290, §13-59). 정지된 작품은 신고 누적이
 * <b>세어서</b> 내린 것이고 (§13-41), 그것을 작성자가 다투었다는 사실은 검수자가 알아야 하는
 * 것이다 — 다만 <b>사유는 담지 않는다</b>. 큐는 무엇을 볼 차례인지를 답하는 자리이며, 작성자가
 * 쓴 글은 원고와 같은 문으로 본다 (S-5).
 *
 * @param queuedAt 언제부터 기다렸는가. 마지막 판정 시각이다
 * @param appealed 작성자가 이번 정지에 대해 재검토를 요청했고 <b>아직 답을 받지 않았다</b>.
 * 순서를 바꾸지 않는다 — 요청은 공짜이므로 앞세우면 줄을 사는 길이 된다
 */
public record ReviewQueueItemResponse(UUID storyId, String title, String reviewStatus,
		Instant queuedAt, boolean appealed) {

	static ReviewQueueItemResponse of(ReviewQueueService.QueueItem item) {
		return new ReviewQueueItemResponse(item.storyId(), item.title(),
				item.reviewStatus().columnValue(), item.queuedAt(), item.appealed());
	}
}

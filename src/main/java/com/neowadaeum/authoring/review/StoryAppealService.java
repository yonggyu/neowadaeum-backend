package com.neowadaeum.authoring.review;

import com.neowadaeum.catalog.publish.StoryPublisher;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 재검토 요청 (#290, §13-59, R8.9).
 *
 * <p><b>자동으로 내려간 것을 사람이 다시 보는 길이다.</b> 신고 누적은 사람의 판정이 아니라
 * 셈이며 (§13-41), 그렇게 내려간 작품에 대고 작성자가 말할 자리가 없으면 화면의 "이의가
 * 있으면 문의해 주세요"는 안내가 아니라 방치다.
 *
 * <p><b>이 요청이 상태를 바꾸지 않는다.</b> 정지된 작품은 <b>이미</b> 인간 검수 큐에 있다
 * ({@link ReviewQueueService#pending()} 이 {@code suspended} 를 함께 본다 — §13-41). 그래서
 * 여기서 {@code review_status} 를 움직이면 <b>작성자가 검수 결과를 되돌리는</b> 길이 된다
 * (I-8). 이 경로가 더하는 것은 둘이다.
 *
 * <ol>
 * <li><b>기록</b> — 작성자가 다투었다는 사실과 그 사유 ({@link StoryAppeal})</li>
 * <li><b>신호</b> — 검수자가 큐에서 그것을 알아본다 ({@code ReviewQueueService.QueueItem#appealed})</li>
 * </ol>
 *
 * <p><b>순서는 바꾸지 않는다.</b> 이의를 앞세우면 <b>아직 아무도 보지 못한</b> 작품들을
 * 제치게 되고, 요청은 공짜다 — 줄을 사는 길을 만들지 않는다.
 *
 * <p><b>사유는 검수자만 읽는다</b> (S-11). 이 요청의 응답에도, 큐의 한 줄에도 실리지 않는다.
 *
 * <p><b>Safety L1 을 태우지 않는다.</b> I-17 이 막는 것은 <b>AI 로 들어가는</b> 무검열 자유
 * 입력이고 이 글은 프롬프트로도 다른 사용자에게도 가지 않는다 — 유일한 독자가 검수자다.
 * 그리고 정지를 다투는 글은 <b>걸린 표현을 인용할 수밖에 없어</b>, 여기에 L1 을 걸면 정확히
 * 이의를 제기해야 하는 사람이 제기하지 못한다. 대신 노출면을 열지 않는 것으로 막는다.
 */
@Service
public class StoryAppealService {

	private final StoryPublisher publisher;

	private final StoryAppealRepository appeals;

	private final Clock clock;

	private final TransactionTemplate transactions;

	public StoryAppealService(StoryPublisher publisher, StoryAppealRepository appeals, Clock clock,
			PlatformTransactionManager catalogTransactionManager) {
		this.publisher = publisher;
		this.appeals = appeals;
		this.clock = clock;
		this.transactions = new TransactionTemplate(catalogTransactionManager);
	}

	/**
	 * 요청한다.
	 *
	 * <p><b>정지된 작품만이다.</b> 사람이 반려한 작품({@code rejected})은 여기로 오지 않는다 —
	 * 그것은 이미 사람이 본 결과이고, 같은 사람에게 다시 보여 달라고 하는 것은 재검토가 아니라
	 * 재요청이다. 고쳐서 다시 내는 길이 그쪽에 이미 있다 (§13-40).
	 *
	 * @param authorRef 요청자의 {@code player_ref}. <b>{@code user.id} 가 아니다</b> (§5.3)
	 * @throws ApiException {@code NOT_FOUND} — 없거나 <b>남의</b> 작품 (I-8). 존재를 알려 주지
	 * 않는 것이 가시성 변경과 같은 규칙이다.
	 * {@code STORY_NOT_SUSPENDED} — 내려가지 않은 작품이다.
	 * {@code ALREADY_EXISTS} — 이번 정지에 대해 이미 요청했고 아직 답을 받지 않았다
	 */
	public void appeal(UUID authorRef, UUID storyId, String reason) {
		this.transactions.executeWithoutResult(status -> {
			StoryPublisher.OwnedStory stored = this.publisher.ownerStatusOf(storyId)
					.filter(owned -> owned.authorRef().equals(authorRef))
					.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));

			if (!ReviewStatus.SUSPENDED.columnValue().equals(stored.reviewStatus())) {
				throw new ApiException(ErrorCode.STORY_NOT_SUSPENDED);
			}
			if (this.appeals.hasOpenAppeal(storyId)) {
				throw new ApiException(ErrorCode.ALREADY_EXISTS);
			}
			this.appeals.save(StoryAppeal.of(storyId, authorRef, reason.strip(),
					Instant.now(this.clock)));
		});
	}
}

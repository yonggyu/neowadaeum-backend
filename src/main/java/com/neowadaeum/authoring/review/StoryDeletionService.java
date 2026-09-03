package com.neowadaeum.authoring.review;

import com.neowadaeum.catalog.publish.StoryPublisher;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 게시된 작품을 지운다 (§13-58, #290-3).
 *
 * <p><b>계약의 {@code DELETE} 는 넷이었고 작품은 그중에 없었다</b> (#290) — 원고는 지울 수
 * 있는데 게시된 작품은 지울 수 없었다. 정지 화면의 [작품 삭제] 가 걸린 자리가 여기다.
 *
 * <p><b>소프트 삭제다.</b> 사용자에게는 '삭제됨'이고 내부는 {@code review_status = 'deleted'}
 * 다. 행을 지우지 않는 이유는 작품에 <b>플레이한 사람들의</b> 기록이 매달려 있기 때문이며
 * (세션·턴·스냅샷·도달률), 그것은 작성자의 것이 아니다 — §13-44 가 탈퇴 파기에 대해 같은
 * 이유로 도달률을 되돌리지 않기로 한 것과 같은 판단이다.
 *
 * <p><b>되돌리는 경로를 만들지 않는다.</b> 사용자에게 '삭제'라고 말한 이상 복구 API 를 함께
 * 두는 쪽이 거짓말이다. {@code deleted} 는 §13-9 상태 머신의 흡수 상태이며, 그 보장은
 * {@link StoryPublisher} 가 든다 — {@code applyReview} 와 {@code suspend} 가 그 행을 건드리지
 * 않고, {@code statusOf} · {@code ownerStatusOf} 가 그 행을 보지 못한다.
 *
 * <p><b>작성자 본인만이다</b> (I-8). 공식 작품({@code author_type = 'official'})은 이 경로로
 * 지워지지 않는다 — {@code story_author_type_ref_check}(catalog V12) 가 공식 작품의
 * {@code author_ref} 를 {@code NULL} 로 못박으므로 <b>어떤 요청자와도 일치하지 않는다.</b>
 * 공식 카탈로그를 내리는 것은 작성자의 처분이 아니라 운영의 조치이고, 그 문은 §14 다.
 */
@Service
public class StoryDeletionService {

	private final StoryPublisher publisher;

	private final TransactionTemplate transactions;

	public StoryDeletionService(StoryPublisher publisher,
			PlatformTransactionManager catalogTransactionManager) {
		this.publisher = publisher;
		this.transactions = new TransactionTemplate(catalogTransactionManager);
	}

	/**
	 * 지운다.
	 *
	 * <p><b>상태를 가리지 않는다.</b> 가시성 변경과 달리 {@code approved} 를 요구하지 않는다 —
	 * 그쪽은 작성자가 <b>검수 결과를 되돌리는</b> 길이 될 수 있어 막지만, 삭제는 어떤 판정도
	 * 되돌리지 않는다. 정지된 작품을 지우는 것은 정지를 푸는 일이 아니라 <b>더 내리는</b>
	 * 일이고, 검수를 기다리던 작품을 지우면 큐에서도 빠진다 (검수자가 아무에게도 보이지 않을
	 * 작품을 읽지 않는다). 신고·검수 이력은 그대로 남는다 — 지운 것은 작품이지 그 작품에 대해
	 * 사람들이 남긴 사실이 아니다.
	 *
	 * @param authorRef 요청자의 {@code player_ref}. <b>{@code user.id} 가 아니다</b> (§5.3)
	 * @throws ApiException {@code NOT_FOUND} — 없거나, <b>남의</b> 작품이거나, <b>이미 지워진</b>
	 * 작품 (I-8). 셋을 구분하지 않는 것이 원고·가시성 변경과 같은 규칙이며, 구분하면 남의
	 * {@code storyId} 하나로 <b>그것이 존재하는지</b>를 물을 수 있게 된다
	 */
	public void delete(UUID authorRef, UUID storyId) {
		this.transactions.executeWithoutResult(status -> {
			// authorRef 를 왼쪽에 둔다. 공식 작품의 author_ref 는 NULL 이므로 (catalog V12)
			// 반대로 쓰면 그 행에서 NPE 가 난다 — 답은 "일치하지 않는다" 여야 한다.
			boolean mine = this.publisher.ownerStatusOf(storyId)
					.filter(owned -> authorRef.equals(owned.authorRef())).isPresent();
			if (!mine || !this.publisher.delete(storyId)) {
				throw new ApiException(ErrorCode.NOT_FOUND);
			}
		});
	}
}

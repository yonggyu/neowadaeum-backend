package com.neowadaeum.admin;

import com.neowadaeum.authoring.review.ReviewHistoryService;
import java.time.Instant;
import java.util.List;

/**
 * 지난 판정 한 줄 (§14, §13-63, #316).
 *
 * <p><b>검수자를 담지 않는다</b> (I-3). {@code reviewer_ref} 는 {@code player_ref} 이고,
 * <b>누가 판정했는지</b>를 답하는 자리는 관리자 감사 기록이다 — 이력은 <b>무엇이 어떻게
 * 판정됐는가</b>를 답한다. 표시명으로 바꿔 싣지도 않는다: catalog 에 있는 표시명은
 * <b>작성자</b>의 것이고 검수자의 것이 아니므로, 실으려면 Identity 를 이 경로에 끌어와야
 * 한다 — 판정을 되짚는 데 필요하지 않은 값 하나를 위해 경계를 여는 일이다.
 *
 * <p><b>{@code note} 는 담는다.</b> 이 경로는 관리자 전용이며 (S-4), 검수자가 문장을 적는
 * 유일한 자리다. <b>작성자 응답에는 가지 않는다</b> (R8.7, S-11).
 *
 * @param stage {@code auto} 인지 {@code human} 인지. 자동 통과는 <b>사람이 본 것이 아니다</b>
 * @param reasons 사유 <b>카테고리만</b>이다 (R8.7). 자동 판정이 남긴 것도 카테고리다
 * @param note 없으면 {@code null} 이다 — 키를 생략하지 않는다
 */
public record ReviewHistoryEntryResponse(String stage, String verdict, List<String> reasons,
		Instant reviewedAt, String note) {

	static ReviewHistoryEntryResponse of(ReviewHistoryService.Entry entry) {
		return new ReviewHistoryEntryResponse(entry.stage().columnValue(),
				entry.verdict().columnValue(), entry.reasons(), entry.reviewedAt(), entry.note());
	}
}

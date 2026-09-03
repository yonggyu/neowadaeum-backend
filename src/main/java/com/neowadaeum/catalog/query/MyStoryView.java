package com.neowadaeum.catalog.query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 내가 만든 작품 한 줄 (§13.7 의 {@code MyStoryItem}, R13.4).
 *
 * <p><b>{@code rejectReasons} 는 카테고리만 담는다</b> (R8.7). 어떤 표현이 걸렸는지 알리면
 * <b>그것을 피해 쓰는 법</b>을 알려주는 셈이고, 그 순간 검수는 우회 가능한 절차가 된다.
 *
 * <p>{@code playCount} 는 play 스토어의 값이므로 여기 없다 — 조립하는 쪽이 채운다 (§5.3).
 *
 * <p><b>{@code submittedAt} · {@code reviewedAt} 은 {@code story} 의 컬럼이 아니다</b> (§13-57,
 * #290). 검수 이력에서 파생하며, 그 규칙은 그 표를 소유한 authoring 이 하나로 갖는다 —
 * {@code updatedAt} 하나로는 <b>신청한 날과 승인된 날</b>을 구분할 수 없다.
 *
 * @param reviewStatus 사용자에게 보이는 값. {@code auto_rejected} 는 {@code rejected} 로 보인다 (§13-9)
 * @param submittedAt 지금 회차의 검수를 요청한 시각. 요청한 적이 없으면 {@code null}
 * @param reviewedAt 그 회차에서 사람이 마지막으로 판정한 시각. 아직 없으면 {@code null}
 */
public record MyStoryView(UUID storyId, String title, String coverImage, String visibility,
		String reviewStatus, List<String> rejectReasons, Instant updatedAt, Instant submittedAt,
		Instant reviewedAt) {

	public MyStoryView {
		rejectReasons = List.copyOf(rejectReasons == null ? List.of() : rejectReasons);
	}
}

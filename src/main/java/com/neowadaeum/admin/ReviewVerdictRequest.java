package com.neowadaeum.admin;

import com.neowadaeum.authoring.review.ReviewVerdict;
import com.neowadaeum.common.spi.SafetyCategory;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 검수 판정 (§14, R8.7, B-55).
 *
 * <p><b>{@code reasons} 는 자유 문자열이 아니다.</b> 이 값은 작성자에게 그대로 전달되므로
 * (R8.7), 검수자가 문장을 적을 수 있으면 <b>걸린 표현이 그 문장에 실려</b> 작성자에게 간다 —
 * 그것이 곧 우회 사전이다 (S-11). 카테고리 열거형만 받는 것이 그 보장이다.
 *
 * <p><b>{@code note} 는 작성자에게 가지 않는다.</b> 검수자끼리 남기는 기록이며, 왜 그렇게
 * 판단했는지는 <b>어딘가에는</b> 남아야 한다.
 *
 * @param note 내부 기록. 없어도 된다
 */
public record ReviewVerdictRequest(@NotNull ReviewVerdict verdict,
		@Size(max = 8) List<SafetyCategory> reasons, @Size(max = 500) String note) {

	/** 사유를 보내지 않아도 된다 — 통과에는 사유가 없다. */
	public ReviewVerdictRequest {
		reasons = (reasons != null) ? List.copyOf(reasons) : List.of();
	}
}

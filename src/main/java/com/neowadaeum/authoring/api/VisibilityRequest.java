package com.neowadaeum.authoring.api;

import com.neowadaeum.authoring.review.Visibility;
import jakarta.validation.constraints.NotNull;

/**
 * 가시성 변경 요청 (§13.8, R8.6, #245).
 *
 * <p><b>{@code public} 은 여기서도 사람이 연다.</b> 이 요청이 여는 것은 검수이지 공개가 아니다 —
 * {@code unlisted} 로 승인받고 {@code public} 으로 올리는 길이 <b>인간 검수를 지나지 않는</b>
 * 경로가 되면 R8.6 이 무의미해진다.
 */
public record VisibilityRequest(@NotNull Visibility visibility) {
}

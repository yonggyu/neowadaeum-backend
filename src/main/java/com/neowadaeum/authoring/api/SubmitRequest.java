package com.neowadaeum.authoring.api;

import com.neowadaeum.authoring.review.Visibility;
import jakarta.validation.constraints.NotNull;

/**
 * 제출 요청 (§13.8, R8.6).
 *
 * <p><b>가시성을 제출과 함께 정한다.</b> 나중에 바꾸게 하면 {@code unlisted} 로 승인받고
 * {@code public} 으로 올리는 길이 생긴다 — 그때는 인간 검수를 지나지 않은 작품이 공개된다.
 * ({@code unlisted} → {@code public} 전환은 <b>재검수 대상</b>이며 B-55 다.)
 */
public record SubmitRequest(@NotNull Visibility visibility) {
}

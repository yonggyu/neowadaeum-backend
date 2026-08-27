package com.neowadaeum.common.spi;

import java.util.UUID;

/**
 * 한 엔딩에 도달한 세션 수 (§2.6, B-39).
 *
 * <p><b>{@code endingId} 로 온다.</b> {@code play} 는 세션이 도달한 엔딩의 식별자만 알고,
 * 그것이 그 작품의 몇 번째 엔딩인지는 <b>{@code catalog} 가 안다</b> (§5.3) — 변환을 저쪽에
 *맡기는 것이 스토어 분리의 실질이다.
 *
 * @param storyId             작품
 * @param endingId            도달한 엔딩. {@code ending_def} 의 식별자다
 * @param reachedCount        그 엔딩에 도달한 세션 수
 * @param storyCompletedCount <b>그 작품에서 완주한 세션 전체 수.</b> 도달률의 분모이며 (R2.8),
 *                            같은 작품의 행마다 같은 값이다
 */
public record EndingReach(UUID storyId, UUID endingId, long reachedCount, long storyCompletedCount) {
}

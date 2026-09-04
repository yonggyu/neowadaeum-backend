package com.neowadaeum.play.repository;

import java.util.UUID;

/**
 * 작품 하나의 플레이 횟수 (#351, R13.4).
 *
 * <p><b>세션이 없는 작품은 여기 없다.</b> {@code GROUP BY} 가 돌려주는 것은 <b>있는 것</b>뿐이며,
 * 0 은 조회가 아니라 <b>부재</b>로 표현된다 — 부르는 쪽이 기본값을 안다.
 *
 * @param playCount {@code COUNT} 의 결과라 {@code Long} 이다 — JPQL 생성자 표현이 그 형을 그대로
 *     넘긴다
 */
public record StoryPlayCount(UUID storyId, Long playCount) {
}

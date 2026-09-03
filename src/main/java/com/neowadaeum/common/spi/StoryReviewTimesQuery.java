package com.neowadaeum.common.spi;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * 검수 시각 조회 (§13-57, #290).
 *
 * <p><b>왜 SPI 인가.</b> {@code story_review} 를 소유하는 것은 {@code authoring} 이고
 * (ADR-0002 와 같은 자리 — catalog 스키마에 있지만 쓰는 쪽은 authoring 하나다), 읽어야 하는
 * 것은 {@code catalog} 다: "내가 만든 작품" 목록이 작품마다 신청·승인 시각을 함께 보여 준다
 * (§13.7). {@code catalog} 의 허용 의존은 {@code common} 하나이므로 authoring 을 직접 부를 수
 * 없고, 부를 수 있게 열면 {@code catalog → authoring → catalog} 순환이 된다. 그래서 계약을
 * {@code common} 에 두고 <b>구현을 데이터 소유 모듈에</b> 둔다 — {@link BlocklistQuery} ·
 * {@link AuthorDisplayNameQuery} 와 같은 형태다.
 *
 * <p><b>목록으로 받고 목록으로 답한다.</b> 작품마다 물으면 20줄짜리 목록이 21번의 조회가 된다
 * (§15 — p95 300ms). 한 쪽을 채우는 데 드는 조회는 <b>쪽 수와 무관하게 하나</b>다.
 */
public interface StoryReviewTimesQuery {

	/**
	 * @param storyIds 조회 대상. 비어 있으면 조회하지 않는다
	 * @return 작품별 시각. <b>검수 이력이 없는 작품은 키 자체가 없다</b> — 호출부가
	 * {@link StoryReviewTimes#NONE} 을 기본값으로 쓴다
	 */
	Map<UUID, StoryReviewTimes> findByStoryIds(Collection<UUID> storyIds);

}

package com.neowadaeum.common.spi;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * 작품에서 그것을 발행한 원고로 가는 길 (§13.7 의 {@code MyStoryItem}, #340).
 *
 * <p><b>왜 SPI 인가.</b> {@code story_draft} 를 소유하는 것은 {@code authoring} 이고, 읽어야 하는
 * 것은 {@code catalog} 다 — "내가 만든 작품" 목록이 작품마다 원고 id 를 함께 실어야 화면이
 * <b>이어서 작성</b>과 <b>반려 사유 자세히 보기</b>({@code GET /authoring/drafts/{draftId}/review})
 * 로 갈 수 있다. {@code catalog} 의 허용 의존은 {@code common} 하나이므로 authoring 을 직접 부를
 * 수 없고, 열면 {@code catalog → authoring → catalog} 순환이 된다.
 * {@link StoryReviewTimesQuery} 와 같은 자리다.
 *
 * <p><b>방향이 이쪽인 것은 저장 구조 그대로다.</b> {@code story} 는 원고를 알지 못하고
 * {@code story_draft} 가 발행한 작품을 기억한다 — 그 사실을 뒤집어 컬럼을 만들면
 * <b>원고를 지워도 남는 값</b>을 catalog 가 들게 된다.
 *
 * <p><b>목록으로 받고 목록으로 답한다</b> (§15). 작품마다 물으면 20줄짜리 목록이 21번의 조회가
 * 된다.
 */
public interface StoryDraftLinkQuery {

	/**
	 * @param storyIds 조회 대상. 비어 있으면 조회하지 않는다
	 * @return 작품별 원고 id. <b>원고 없이 존재하는 작품은 키 자체가 없다</b> — 미리보기가
	 * 발행한 임시 작품이 그렇다 (§13-5). 호출부가 {@code null} 로 채운다
	 */
	Map<UUID, UUID> findDraftIdsByStoryIds(Collection<UUID> storyIds);

}

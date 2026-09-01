package com.neowadaeum.common.spi;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 미리보기가 쌓아 둔 작품의 파기 (§13-37, R8.12, B-61, ADR-0003).
 *
 * <p><b>미리보기는 부를 때마다 작품을 하나씩 발행한다</b> (§13-5). {@code private} · {@code draft}
 * 이므로 아무에게도 보이지 않지만 <b>쌓이는 것은 사실</b>이고, 일일 횟수 상한(R8.12)은 <b>속도를
 * 늦출 뿐 쌓이는 것을 멈추지 않는다.</b>
 *
 * <p><b>지워도 잃는 것이 없다.</b> 작성자의 원고는 {@code story_draft} 에 그대로 있고 미리보기는
 * 그것의 사본이다 — 다시 보고 싶으면 다시 부르면 된다.
 *
 * <p><b>두 단계로 나뉜 것은 순서 때문이다.</b> 작품을 먼저 지우면 그 위에서 돌던 세션은
 * <b>읽을 수 없는 기록</b>이 되고, 그 세션을 찾을 근거도 함께 사라진다. 그래서 대상을 먼저
 * 묻고, play 가 세션을 지운 뒤, 마지막에 작품을 지운다.
 *
 * @see PreviewSessionPurge
 */
public interface PreviewStoryPurge {

	/**
	 * 보관 기간이 지난 미리보기 작품의 id 들.
	 *
	 * <p><b>제출된 작품은 여기 들어오지 않는다.</b> 제출 경로는 같은 트랜잭션 안에서 검수 상태를
	 * 옮기므로 (R8.6), {@code draft} 로 남아 있는 {@code private} UGC 작품은 미리보기뿐이다.
	 */
	List<UUID> expiredPreviewStories();

	/**
	 * 작품과 그 버전에 매달린 것들을 지운다.
	 *
	 * @param storyIds {@link #expiredPreviewStories()} 가 준 값들
	 * @return 지워진 작품 수. batch 가 구조화 로그에 남긴다 (§9.4)
	 */
	int purge(Collection<UUID> storyIds);
}

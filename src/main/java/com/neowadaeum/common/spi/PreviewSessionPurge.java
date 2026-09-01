package com.neowadaeum.common.spi;

import java.util.Collection;
import java.util.UUID;

/**
 * 미리보기 세션의 파기 (§13-37, B-61, ADR-0003).
 *
 * <p><b>작품과 한 벌이다.</b> 미리보기 세션은 그 작품 위에서만 의미가 있고 3턴에서 끝난다
 * (R8.13) — 작품이 사라지면 그 세션은 <b>읽을 수 없는 기록</b>이 된다.
 *
 * <p><b>작품보다 먼저 불린다.</b> 작품을 먼저 지우면 세션을 찾을 근거가 사라진다
 * ({@link PreviewStoryPurge} 의 설명).
 *
 * @see PreviewStoryPurge
 */
public interface PreviewSessionPurge {

	/**
	 * 그 작품들 위의 세션을 지운다.
	 *
	 * <p><b>{@code is_test_session} 을 조건에 넣지 않는다.</b> 지우는 근거는 세션의 성질이 아니라
	 * <b>작품이 사라진다는 사실</b>이며, 그 작품 위에 다른 세션이 있다면 그것도 읽을 수 없게 된다.
	 *
	 * @return 지워진 세션 수. batch 가 구조화 로그에 남긴다 (§9.4)
	 */
	int purgeByStories(Collection<UUID> storyIds);
}

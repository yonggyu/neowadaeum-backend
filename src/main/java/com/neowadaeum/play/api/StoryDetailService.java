package com.neowadaeum.play.api;

import com.neowadaeum.catalog.query.StoryBriefView;
import com.neowadaeum.catalog.query.StoryCatalogFacade;
import com.neowadaeum.catalog.query.StoryDetailView;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.domain.SessionStatus;
import com.neowadaeum.play.repository.PlaySessionRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

/**
 * 작품 상세 조립 (§13.3, B-16).
 *
 * <p>라이브러리와 같은 구조다 — 작품은 catalog, 진행 중 세션은 play 이며 <b>애플리케이션
 * 레벨에서만</b> 만난다 (§5.3, ADR-0006).
 */
@Service
public class StoryDetailService {

	/** 작품당 {@code active} 세션은 1개다 (§13-9). 그래도 한 건만 읽는다는 것을 명시한다. */
	private static final Limit ONE = Limit.of(1);

	private final StoryCatalogFacade stories;

	private final PlaySessionRepository sessions;

	public StoryDetailService(StoryCatalogFacade stories, PlaySessionRepository sessions) {
		this.stories = stories;
		this.sessions = sessions;
	}

	/**
	 * @throws ApiException {@code NOT_FOUND} — 없거나 <b>볼 수 없는</b> 작품. 둘을 구분하지 않는다
	 *     (I-8) — 구분하면 승인 대기 중인 작품의 존재가 id 하나로 확인된다
	 */
	public StoryDetailResponse detail(UUID playerRef, UUID storyId) {
		StoryDetailView story = this.stories.detail(storyId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));

		return new StoryDetailResponse(StoryDetailResponse.Story.from(story), story.characters(),
				mySession(playerRef, storyId));
	}

	/**
	 * 진행 중인 내 세션 (§13.3).
	 *
	 * <p><b>{@code playerRef} 로만 찾는다</b> (I-3) — 남의 세션이 섞일 경로가 없다.
	 *
	 * <p>챕터 제목은 <b>그 세션이 고정한 버전</b>에서 온다 (I-4). 상세 화면의 다른 값들은
	 * 최신 버전을 보여 주지만, 이어하기 카드만은 세션이 보던 것을 가리켜야 한다.
	 */
	private StoryDetailResponse.MySession mySession(UUID playerRef, UUID storyId) {
		List<PlaySession> active = this.sessions
				.findByPlayerRefAndStoryIdAndStatusAndDeletedAtIsNullOrderByUpdatedAtDesc(playerRef, storyId,
						SessionStatus.ACTIVE, ONE);
		if (active.isEmpty()) {
			return null;
		}
		PlaySession session = active.getFirst();
		Map<UUID, StoryBriefView> briefs = this.stories.briefs(List.of(session.getStoryVersionId()));
		String chapterTitle = java.util.Optional.ofNullable(briefs.get(session.getStoryVersionId()))
				.flatMap(brief -> brief.chapterTitle(session.getChapterNo()))
				.orElse(null);

		return new StoryDetailResponse.MySession(session.getId(), session.getChapterNo(), chapterTitle,
				session.getLastSceneSummary());
	}
}

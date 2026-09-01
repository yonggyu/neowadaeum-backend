package com.neowadaeum.play.api;

import com.neowadaeum.catalog.query.StoryBriefView;
import com.neowadaeum.catalog.query.StoryCatalogFacade;
import com.neowadaeum.catalog.query.StoryStatusView;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.domain.SessionStatus;
import com.neowadaeum.play.repository.PlaySessionRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 이어하기 판정 (§13.4, §4.7, B-17).
 *
 * <p><b>판정 순서가 이 클래스의 전부다.</b> 여러 조건이 동시에 참일 수 있으므로 — 지운 세션의
 * 작품이 정지됐고 버전도 바뀌었을 수 있다 — <b>사용자가 할 수 있는 일이 가장 적은 쪽</b>부터
 * 본다. 순서를 바꾸면 "지웠는데 버전이 바뀌었다고 안내받는" 화면이 나온다.
 */
@Service
public class SessionResumeService {

	private final PlaySessionRepository sessions;

	private final StoryCatalogFacade stories;

	public SessionResumeService(PlaySessionRepository sessions, StoryCatalogFacade stories) {
		this.sessions = sessions;
		this.stories = stories;
	}

	/**
	 * @throws ApiException {@code NOT_FOUND} — 없거나 <b>남의</b> 세션. 둘을 구분하지 않는다 (I-3)
	 */
	public ResumeView resume(UUID playerRef, UUID sessionId) {
		PlaySession session = requireOwned(playerRef, sessionId);
		Optional<StoryStatusView> story = this.stories.status(session.getStoryId());
		StoryBriefView brief = this.stories.briefs(List.of(session.getStoryVersionId()))
				.get(session.getStoryVersionId());

		return new ResumeView(session.getId(), session.getStoryId(),
				(brief != null) ? brief.title() : null,
				session.getChapterNo(),
				(brief != null) ? brief.chapterTitle(session.getChapterNo()).orElse(null) : null,
				(brief != null) ? brief.totalChapters() : 0,
				session.getTurnNo(), session.getUpdatedAt(),
				// P3 — 장면 이미지는 아직 발행하지 않는다. 자리만 둔다.
				null,
				session.getLastSceneSummary(), session.getLastChoiceText(),
				stateOf(session, story).wire(),
				// B-35 가 조회를 만든다. 볼 것이 있는가는 지금도 답할 수 있다.
				session.getTurnNo() >= 1);
	}

	/**
	 * <b>가장 좁은 조건부터 본다</b> (§4.7).
	 *
	 * <p>지운 것이 먼저다 — 지운 세션에 "버전이 바뀌었습니다"를 안내하면 사용자는 자기가 무엇을
	 * 했는지 헷갈린다. 그다음이 만료, 그다음이 작품 정지(R8.10 — 작품 전체의 문제이므로 개별
	 * 버전 차이보다 앞선다), 마지막이 버전 변경이다.
	 */
	private static ResumeView.State stateOf(PlaySession session, Optional<StoryStatusView> story) {
		if (session.getDeletedAt() != null) {
			return ResumeView.State.DELETED;
		}
		if (session.getStatus() == SessionStatus.EXPIRED) {
			return ResumeView.State.EXPIRED;
		}
		if (story.isEmpty() || story.get().suspended()) {
			// 작품이 사라진 경우도 여기로 둔다 — 이어갈 수 없다는 사실은 같고, 사용자에게
			// "없는 작품"과 "정지된 작품"을 구분해 알릴 이유가 없다 (I-8 과 같은 판단).
			return ResumeView.State.STORY_SUSPENDED;
		}
		if (!session.getStoryVersionId().equals(story.get().currentVersionId())) {
			return ResumeView.State.VERSION_CHANGED;
		}
		return ResumeView.State.VALID;
	}

	/** <b>남의 세션은 없는 것과 구분되지 않는다</b> (I-3). */
	private PlaySession requireOwned(UUID playerRef, UUID sessionId) {
		PlaySession session = this.sessions.findById(sessionId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!session.getPlayerRef().equals(playerRef)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return session;
	}
}

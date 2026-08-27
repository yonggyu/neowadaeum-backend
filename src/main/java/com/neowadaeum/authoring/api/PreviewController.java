package com.neowadaeum.authoring.api;

import com.neowadaeum.authoring.draft.DraftService;
import com.neowadaeum.authoring.draft.DraftStoryDefinition;
import com.neowadaeum.catalog.publish.StoryDefinition;
import com.neowadaeum.catalog.publish.StoryPublisher;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.common.spi.TestSessionStarter;
import com.neowadaeum.common.support.RateLimiter;
import com.neowadaeum.common.web.PlayerRefResolver;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 미리보기 (§13.8, R8.13, §13-5).
 *
 * <p><b>미리보기 시점에는 작품이 없다.</b> 그런데 세션은 버전을 요구하고 파이프라인 전체가
 * <b>"버전이 있다"</b>를 전제로 서 있다 — §13-5 의 채택안대로 <b>임시 작품과 버전을 발행</b>한다.
 * {@code private} · {@code draft} 이므로 라이브러리에 뜨지 않는다 (R2.3, I-8).
 *
 * <p><b>현재 버전으로 만들지 않는다</b> (R8.8). 검수를 통과하기 전에는 버전이 있어도
 * <b>현재</b>가 아니다 — {@code markCurrent} 는 게시(B-56)가 부른다.
 *
 * <p><b>3턴이다</b> (R8.13). 상한은 세션에 박히고 서버가 막는다.
 */
@RestController
@RequestMapping("/api/v1/authoring/drafts/{draftId}/preview")
public class PreviewController {

	/** R8.13 — 3턴 후 자동 종료. */
	static final int PREVIEW_TURN_LIMIT = 3;

	/**
	 * 계정당 일일 미리보기 횟수 (R8.12).
	 *
	 * <p>미리보기 한 번이 <b>임시 작품 하나와 AI 호출 셋</b>이다. 상한이 없으면 그 비용을
	 * 한 계정이 정한다.
	 */
	static final int PREVIEWS_PER_DAY = 10;

	/** R4.4 · §13-9 — 작성자가 자유 정의하지 않는다. 미리보기는 기본 템플릿으로 연다. */
	private static final String PREVIEW_STATE_SCHEMA = "{\"flags\":[]}";

	private final DraftService drafts;

	private final StoryPublisher publisher;

	private final TestSessionStarter sessions;

	private final RateLimiter rateLimiter;

	private final PlayerRefResolver playerRefs;

	public PreviewController(DraftService drafts, StoryPublisher publisher,
			TestSessionStarter sessions, RateLimiter rateLimiter, PlayerRefResolver playerRefs) {
		this.drafts = drafts;
		this.publisher = publisher;
		this.sessions = sessions;
		this.rateLimiter = rateLimiter;
		this.playerRefs = playerRefs;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public PreviewResponse preview(@PathVariable UUID draftId) {
		UUID authorRef = this.playerRefs.currentPlayerRef();
		requireWithinDailyLimit(authorRef);
		// 남의 원고에는 부를 수 없다 (I-8). 없는 것과 구분되지 않는다.
		String payload = this.drafts.read(authorRef, draftId).getPayload();

		StoryDefinition definition = DraftStoryDefinition.from(authorRef, payload);
		StoryPublisher.PublishedVersion published = this.publisher.publishNew(definition,
				PREVIEW_STATE_SCHEMA);

		TestSessionStarter.TestSession session = this.sessions.start(authorRef, published.storyId(),
				published.versionId(), PREVIEW_TURN_LIMIT);
		return new PreviewResponse(session.sessionId(), session.turnNo(), PREVIEW_TURN_LIMIT);
	}

	private void requireWithinDailyLimit(UUID authorRef) {
		if (!this.rateLimiter.tryAcquire("preview-day", authorRef.toString(), PREVIEWS_PER_DAY,
				Duration.ofDays(1))) {
			throw new ApiException(ErrorCode.RATE_LIMITED,
					Map.of("retryAfterSeconds", Duration.ofDays(1).toSeconds()));
		}
	}
}

package com.neowadaeum.authoring.api;

import com.neowadaeum.authoring.review.StoryVisibilityService;
import com.neowadaeum.common.web.PlayerRefResolver;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공개 범위 변경 (§13.8, R8.6, B-55, #245).
 *
 * <p><b>계약에 있었고 구현이 없었다</b> (#245). 그래서 이미 승인된 {@code unlisted} 작품을
 * {@code public} 으로 올릴 방법이 없었다 — B-55 의 완료 조건 중 <b>재검수 트리거</b>가 그것이다.
 *
 * <p><b>남의 작품은 바꿀 수 없다</b> (I-8). 판정은 서비스가 한다.
 */
@RestController
@RequestMapping("/api/v1/stories/{storyId}")
public class StoryVisibilityController {

	private final StoryVisibilityService visibility;

	private final PlayerRefResolver playerRefs;

	private final Clock clock;

	public StoryVisibilityController(StoryVisibilityService visibility,
			PlayerRefResolver playerRefs, Clock clock) {
		this.visibility = visibility;
		this.playerRefs = playerRefs;
		this.clock = clock;
	}

	/**
	 * <b>200 이다.</b> 승격은 검수를 기다리게 되고 하향은 즉시 반영되지만, 둘을 다른 상태 코드로
	 * 나누면 클라이언트가 <b>둘을 다르게 다루게</b> 된다 — 응답의 {@code reviewStatus} 가 이미
	 * 무엇이 일어났는지 말한다.
	 */
	@PatchMapping("/visibility")
	public ReviewStatusResponse change(@PathVariable UUID storyId,
			@Valid @RequestBody VisibilityRequest body) {
		StoryVisibilityService.VisibilityOutcome outcome = this.visibility
				.change(this.playerRefs.currentPlayerRef(), storyId, body.visibility());
		return ReviewStatusResponse.of(outcome, Instant.now(this.clock));
	}
}

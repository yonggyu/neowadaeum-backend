package com.neowadaeum.play.api;

import com.neowadaeum.common.web.PlayerRefResolver;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 작품 상세 API (§13.3, 화면 2.2).
 *
 * <p><b>Controller 는 요청 검증과 DTO 변환만 한다</b> (web-api 규칙).
 *
 * <p>{@code play} 에 있는 이유는 라이브러리와 같다 — 이 화면도 catalog 의 작품과 play 의 세션을
 * 함께 담고, 의존은 {@code play → catalog :: query} 한 방향만 열려 있다 (ADR-0006, §13-25).
 */
@RestController
public class StoryDetailController {

	private final PlayerRefResolver playerRefs;

	private final StoryDetailService detail;

	public StoryDetailController(PlayerRefResolver playerRefs, StoryDetailService detail) {
		this.playerRefs = playerRefs;
		this.detail = detail;
	}

	@GetMapping("/api/v1/stories/{storyId}")
	public StoryDetailResponse story(@PathVariable UUID storyId) {
		return this.detail.detail(this.playerRefs.currentPlayerRef(), storyId);
	}
}

package com.neowadaeum.play.api;

import com.neowadaeum.common.web.PlayerRefResolver;
import com.neowadaeum.common.web.PublicReadGuard;
import jakarta.servlet.http.HttpServletRequest;
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
 *
 * <p><b>인증 밖으로 열려 있다</b> (§13-54, 이슈 #306) — 라이브러리와 같은 이유다. 익명이면
 * {@code mySession} 이 {@code null} 로 나가며, <b>보이는 작품은 달라지지 않는다</b>: 노출 조건은
 * {@code StoryCatalogFacade} 의 SQL 한 곳에 있고 인증은 그것을 대신한 적이 없다 (I-8).
 */
@RestController
public class StoryDetailController {

	private final PlayerRefResolver playerRefs;

	private final StoryDetailService detail;

	private final PublicReadGuard publicReads;

	public StoryDetailController(PlayerRefResolver playerRefs, StoryDetailService detail,
			PublicReadGuard publicReads) {
		this.playerRefs = playerRefs;
		this.detail = detail;
		this.publicReads = publicReads;
	}

	@GetMapping("/api/v1/stories/{storyId}")
	public StoryDetailResponse story(@PathVariable UUID storyId, HttpServletRequest request) {
		this.publicReads.requireWithinBrowseIpLimit(request);
		return this.detail.detail(this.playerRefs.currentPlayerRefIfAuthenticated(), storyId);
	}
}

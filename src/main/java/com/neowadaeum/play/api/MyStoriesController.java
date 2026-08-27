package com.neowadaeum.play.api;

import com.neowadaeum.common.web.PlayerRefResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 것들 API (§13.7, 화면 2.4).
 *
 * <p><b>Controller 는 요청 검증과 DTO 변환만 한다</b> (web-api 규칙).
 *
 * <p>{@code play} 에 있는 이유는 라이브러리·상세와 같다 — 두 스토어를 담고, 의존은
 * {@code play → catalog :: query} 한 방향만 열려 있다 (ADR-0006, §13-25).
 */
@RestController
@RequestMapping("/api/v1/me")
public class MyStoriesController {

	private final PlayerRefResolver playerRefs;

	private final MyStoriesService myStories;

	public MyStoriesController(PlayerRefResolver playerRefs, MyStoriesService myStories) {
		this.playerRefs = playerRefs;
		this.myStories = myStories;
	}

	/**
	 * 진행 중 / 완료 (§13.7).
	 *
	 * <p><b>{@code status} 는 필수다.</b> 기본값을 두면 어느 탭인지 모르는 요청이 한쪽으로
	 * 흘러가고, 그 한쪽이 조용히 정답이 된다.
	 */
	@GetMapping("/sessions")
	public MyStoriesView.Sessions sessions(@RequestParam String status,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit) {
		return this.myStories.sessions(this.playerRefs.currentPlayerRef(), status, cursor, limit);
	}

	/** 내가 만든 작품 (R13.4). */
	@GetMapping("/stories")
	public MyStoriesView.Stories stories(@RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit) {
		return this.myStories.stories(this.playerRefs.currentPlayerRef(), cursor, limit);
	}
}

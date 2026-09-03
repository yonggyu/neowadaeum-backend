package com.neowadaeum.play.api;

import com.neowadaeum.common.web.PlayerRefResolver;
import com.neowadaeum.common.web.PublicReadGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 라이브러리 API (§13.2, 화면 2.1).
 *
 * <p><b>Controller 는 요청 검증과 DTO 변환만 한다</b> (web-api 규칙).
 *
 * <p><b>{@code play} 에 있는 이유</b> — 이 화면은 catalog 의 작품과 play 의 세션을 함께 담는데
 * 모듈 의존은 {@code play → catalog :: query} 한 방향만 열려 있다 (ADR-0006, §13-25).
 *
 * <p><b>인증 밖으로 열려 있다</b> (§13-54, 이슈 #306). 그래서 {@link PlayerRefResolver} 에게
 * <b>누구인지 물을 뿐 요구하지 않는다</b> — {@code currentPlayerRef()} 를 부르면 익명 요청이
 * 401 로 끝난다. 익명이면 {@code continueSessions} 가 빈 배열로 나간다.
 *
 * <p>그래서 <b>{@link PublicReadGuard} 를 받는다</b> (S-8). 셀 계정이 없는 경로이므로 IP 로
 * 세며, <b>탐색 셋이 한 창을 쓰고 설정 조회와는 나뉜다</b> — 이유는 그 클래스에 적혀 있다.
 */
@RestController
@RequestMapping("/api/v1/library")
public class LibraryController {

	private final PlayerRefResolver playerRefs;

	private final LibraryService library;

	private final PublicReadGuard publicReads;

	public LibraryController(PlayerRefResolver playerRefs, LibraryService library,
			PublicReadGuard publicReads) {
		this.playerRefs = playerRefs;
		this.library = library;
		this.publicReads = publicReads;
	}

	@GetMapping
	public LibraryView library(HttpServletRequest request) {
		this.publicReads.requireWithinBrowseIpLimit(request);
		return this.library.library(this.playerRefs.currentPlayerRefIfAuthenticated());
	}

	/** 섹션 단위 재시도와 더 보기 (§13.2). 모르는 키는 {@code 404} 다. */
	@GetMapping("/sections/{sectionKey}")
	public LibraryView.SectionView section(@PathVariable String sectionKey,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit, HttpServletRequest request) {
		this.publicReads.requireWithinBrowseIpLimit(request);
		return this.library.section(sectionKey, cursor, limit);
	}
}

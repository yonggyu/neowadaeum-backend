package com.neowadaeum.play.api;

import java.util.List;
import java.util.UUID;

/**
 * 랜딩 (§13.10).
 *
 * <p><b>{@code isLoggedIn} 이 없다.</b> 클라이언트가 토큰 보유로 판단하며, 서버가 줄 필요가 없다 —
 * 주면 <b>인증 없이 열린 경로가 인증 상태를 알려주는</b> 이상한 모양이 된다.
 *
 * @param noticeText AI 사전 고지 문구. <b>코드에 없다</b> — {@code service_config} 에서 온다 (R11.1)
 */
public record LandingView(List<FeaturedStory> featuredStories, String noticeText) {

	public LandingView {
		featuredStories = List.copyOf(featuredStories == null ? List.of() : featuredStories);
	}

	/** 첫 화면의 작품 카드. 라이브러리 카드보다 좁다 — 로그인 전이므로 진행 정보가 없다. */
	public record FeaturedStory(UUID storyId, String title, String coverImage) {
	}
}

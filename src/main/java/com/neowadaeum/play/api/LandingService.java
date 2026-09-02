package com.neowadaeum.play.api;

import com.neowadaeum.catalog.query.LibrarySectionKey;
import com.neowadaeum.catalog.query.StoryCardView;
import com.neowadaeum.catalog.query.StoryCatalogFacade;
import com.neowadaeum.common.error.ApiException;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 랜딩 조립 (§13.10, B-37).
 *
 * <p><b>고지 문구가 없으면 실패한다.</b> §11 은 사전 고지를 <b>의무</b>로 규정하고 R11.1 은
 * 그것을 하드코딩하지 말라고 한다 — 두 요구를 함께 지키는 유일한 방법은 <b>문구 없이 이 화면을
 * 내보내지 않는 것</b>이다. 빈 문자열을 주면 고지가 없는 상태가 정상으로 보인다.
 *
 * <p>추천 작품은 라이브러리와 <b>같은 파사드</b>를 쓴다. 노출 조건(R2.3, I-8)이 한 곳에 있어야
 * 하며, 첫 화면만 조건이 느슨하면 그것이 유출 경로가 된다.
 */
@Service
public class LandingService {

	/** 첫 화면에 거는 작품 수. 스크롤 없이 보이는 만큼이다. */
	private static final int FEATURED = 6;


	private final StoryCatalogFacade stories;

	private final AiNoticeText notice;

	public LandingService(StoryCatalogFacade stories, AiNoticeText notice) {
		this.stories = stories;
		this.notice = notice;
	}

	/**
	 * @throws ApiException {@code INTERNAL_ERROR} — 고지 문구가 설정되지 않았다. <b>운영 결함이며
	 *     조용히 넘어가서는 안 된다</b> (R11.1, §11)
	 */
	public LandingView landing() {
		String noticeText = this.notice.require("landing");

		List<StoryCardView> cards = this.stories
				.cards(new LibrarySectionKey(LibrarySectionKey.Kind.RECOMMENDED, null), null, FEATURED)
				.stories();

		return new LandingView(cards.stream()
				.map(card -> new LandingView.FeaturedStory(card.storyId(), card.title(), card.coverImage()))
				.toList(), noticeText);
	}
}

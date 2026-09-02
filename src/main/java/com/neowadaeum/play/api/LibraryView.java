package com.neowadaeum.play.api;

import com.neowadaeum.catalog.query.GenreView;
import com.neowadaeum.catalog.query.StoryCardView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 라이브러리 응답 (§13.2 의 {@code LibraryResponse}).
 *
 * <p><b>이 화면은 두 스토어를 담는다</b> — 작품은 catalog, 이어하기는 play 다. 조립이 여기
 * {@code play} 에 있는 이유는 모듈 의존이 {@code play → catalog :: query} 한 방향만 열려 있기
 * 때문이다 (ADR-0006, §13-25).
 *
 * <p><b>{@code noticeText} 가 여기 있는 이유</b> — 고지 문구는 이 화면의 Footer 에 <b>상시</b>
 * 표시된다. 이것을 주지 않으면 클라이언트가 화면마다 {@code /landing} 을 한 번 더 부르게 되고,
 * 두 응답의 캐시 수명이 갈리면 <b>같은 화면에서 다른 문구</b>가 보인다 (#257).
 *
 * @param genres           화면이 보여 줄 장르 목록
 * @param sections         작품 섹션. <b>공식과 사용자는 섞이지 않는다</b> (R13.1)
 * @param continueSessions 진행 중인 세션. 자기 것만이다 (I-3)
 * @param noticeText       AI 사전 고지 문구. <b>코드에 없다</b> — {@code service_config} 에서 온다
 *                         (R11.1)
 */
public record LibraryView(List<GenreView> genres, List<SectionView> sections,
		List<ContinueSessionView> continueSessions, String noticeText) {

	/**
	 * 섹션 하나 (§13.2 의 {@code LibrarySection}).
	 *
	 * <p>{@code hasMore} 와 {@code nextCursor} 는 같은 사실의 두 표현이다 — 계약이 둘 다
	 * 요구하므로 둘 다 내되, <b>한 값에서 파생시킨다.</b>
	 */
	public record SectionView(String sectionKey, String sectionTitle, boolean hasMore,
			List<StoryCardView> stories, String nextCursor) {
	}

	/**
	 * 이어하기 카드 (§13.2 의 {@code ContinueSession}).
	 *
	 * <p><b>진행률을 백분율로 주지 않는다</b> (R13.2, R7.5). AI 생성이라 챕터당 턴 수가
	 * 가변이므로 백분율에 근거가 없다 — 몇 장 중 몇 장인지만 준다.
	 */
	public record ContinueSessionView(UUID sessionId, UUID storyId, String title, String coverImage,
			int chapterNo, String chapterTitle, int totalChapters, String lastSceneSummary,
			Instant updatedAt) {
	}
}

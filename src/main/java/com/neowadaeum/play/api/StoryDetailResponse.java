package com.neowadaeum.play.api;

import com.neowadaeum.catalog.query.CharacterCardView;
import com.neowadaeum.catalog.query.StoryDetailView;
import java.util.List;
import java.util.UUID;

/**
 * 작품 상세 응답 (§13.3 의 {@code StoryDetailResponse}).
 *
 * <p><b>{@code characters} 가 빈 배열이면 클라이언트가 섹션을 숨긴다</b> — {@code null} 을 주지
 * 않는다 (§13.3).
 *
 * @param mySession 진행 중인 내 세션. 없으면 {@code null} — 이 화면에서 "이어하기"가 뜰지를 정한다
 */
public record StoryDetailResponse(Story story, List<CharacterCardView> characters, MySession mySession) {

	/**
	 * 작품 한 벌.
	 *
	 * <p><b>{@code ageRating} 은 상수다</b> (I-19, R10.1). 서비스 전체가 15세 이용가 단일 등급이며
	 * 작품별 등급 컬럼을 두지 않는다 — 그것을 두는 순간 등급별 프롬프트·검수 기준·본인인증이
	 * 함께 따라온다 (R10.5).
	 */
	public record Story(UUID storyId, String title, String heroImage, List<String> genres, String description,
			String worldIntro, String ageRating, String authorType, String authorDisplayName,
			int totalChapters, int totalEndings) {

		/** §10 — 서비스 단일 등급. 계약이 {@code const} 로 못박은 값이다. */
		static final String AGE_RATING = "15세 이용가";

		static Story from(StoryDetailView view) {
			return new Story(view.storyId(), view.title(), view.heroImage(), view.genres(),
					view.description(), view.worldIntro(), AGE_RATING, view.authorType(),
					view.authorDisplayName(), view.totalChapters(), view.totalEndings());
		}
	}

	/**
	 * 내 세션 요약 (§13.3 의 {@code MySessionBrief}).
	 *
	 * <p>세션이 <b>고정한 버전</b>의 챕터 제목이다 (I-4) — 새 버전이 발행돼도 진행 중인 세션은
	 * 자기가 보던 것을 가리킨다.
	 */
	public record MySession(UUID sessionId, int chapterNo, String chapterTitle, String lastSceneSummary) {
	}
}

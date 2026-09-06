package com.neowadaeum.catalog.query;

import java.util.List;
import java.util.UUID;

/**
 * 작품 상세 (§13.3 의 {@code StoryDetail} + {@code characters}).
 *
 * <p><b>{@code ageRating} 이 여기 없다.</b> 그것은 작품의 속성이 아니라 <b>서비스의 상수</b>이며
 * (I-19, R10.1), 조회 결과에 담으면 언젠가 컬럼이 된다.
 *
 * <p><b>{@code authorRef}(= {@code playerRef}) 도 없다.</b> 화면이 쓰는 것은 표시명이고,
 * 식별자를 함께 내보내면 그 값이 클라이언트 로그로 퍼진다 (§13-7, I-3).
 *
 * <p><b>{@code heroImage} 는 커버 · 초상과 같은 판정을 지난다</b> (#395, §13-79). 공식 작품의
 * 값은 이미 주소이므로 그대로 나가고, UGC 는 승인된 것만 서명된다 — <b>이 값을 담은 응답을
 * 캐시하지 않는다</b> (URL 이 응답보다 먼저 만료된다). 지금 UGC 발행은 이 자리를 채우지 않지만,
 * 그때 판정을 다시 정하게 두지 않는다.
 *
 * @param authorDisplayName UGC 작성자 닉네임. 공식 작품이면 {@code null}
 * @param totalEndings      <b>{@code is_secret = false} 인 엔딩만</b> (R7.11)
 */
public record StoryDetailView(UUID storyId, String title, String heroImage, List<String> genres,
		String description, String worldIntro, String authorType, String authorDisplayName,
		int totalChapters, int totalEndings, List<CharacterCardView> characters) {

	public StoryDetailView {
		genres = List.copyOf(genres == null ? List.of() : genres);
		characters = List.copyOf(characters == null ? List.of() : characters);
	}
}

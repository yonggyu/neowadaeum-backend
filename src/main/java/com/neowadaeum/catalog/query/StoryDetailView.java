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

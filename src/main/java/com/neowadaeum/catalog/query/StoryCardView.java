package com.neowadaeum.catalog.query;

import java.util.List;
import java.util.UUID;

/**
 * 라이브러리 카드 한 장 (§13.2 의 {@code StoryCard}).
 *
 * <p><b>{@code authorType} 이 응답에 있는 이유</b>는 R13.1 이다 — 공식 작품과 사용자 작품을
 * 화면이 구분해 표시해야 한다. 섹션을 나누는 것만으로는 부족하다고 원문이 못박는다.
 *
 * @param genres      장르 <b>표기</b>({@code key})의 목록. 라벨은 화면이 매핑한다
 * @param isNew       최근에 발행됐는가. 기준은 {@link StoryCatalogFacade} 가 정한다
 * @param authorType  {@code official} 또는 {@code user}
 */
public record StoryCardView(UUID storyId, String title, String coverImage, List<String> genres,
		String shortDescription, boolean isNew, String authorType) {

	public StoryCardView {
		genres = List.copyOf(genres == null ? List.of() : genres);
	}
}

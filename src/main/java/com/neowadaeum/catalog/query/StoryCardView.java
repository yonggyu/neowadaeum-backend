package com.neowadaeum.catalog.query;

import java.util.List;
import java.util.UUID;

/**
 * 라이브러리 카드 한 장 (§13.2 의 {@code StoryCard}).
 *
 * <p><b>{@code authorType} 이 응답에 있는 이유</b>는 R13.1 이다 — 공식 작품과 사용자 작품을
 * 화면이 구분해 표시해야 한다. 섹션을 나누는 것만으로는 부족하다고 원문이 못박는다.
 *
 * <p><b>{@code authorDisplayName} 도 같은 이유로 있다</b> (R13.1, §13-7, #258). 커뮤니티 섹션은
 * <b>누구의 작품인가</b>를 표기해야 하는데 {@code authorType} 만으로는 "사용자 작품"까지밖에
 * 말하지 못한다. 카드마다 상세를 부르면 목록 화면에서 N+1 이 된다.
 *
 * @param genres            장르 <b>표기</b>({@code key})의 목록. 라벨은 화면이 매핑한다
 * @param isNew             최근에 발행됐는가. 기준은 {@link StoryCatalogFacade} 가 정한다
 * @param authorType        {@code official} 또는 {@code user}
 * @param authorDisplayName UGC 작성자 닉네임. <b>공식 작품이거나 프로필이 없으면
 *     {@code null}</b> 이다 — {@code playerRef} 를 대신 내보내지 않는다 (I-3, §13-7)
 */
public record StoryCardView(UUID storyId, String title, String coverImage, List<String> genres,
		String shortDescription, boolean isNew, String authorType, String authorDisplayName) {

	public StoryCardView {
		genres = List.copyOf(genres == null ? List.of() : genres);
	}
}

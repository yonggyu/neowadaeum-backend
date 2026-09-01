package com.neowadaeum.catalog.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 이어하기 카드가 필요한 작품 쪽 정보 (§13.2).
 *
 * <p><b>버전에 매달린다</b> (I-4, R2.1). 세션은 시작 시점의 버전을 고정하므로, 새 버전이
 * 발행돼도 이어하기 카드는 <b>그 세션이 보던 챕터 제목</b>을 보여 줘야 한다.
 *
 * @param chapters 그 버전의 챕터 제목 전부. 몇 개 되지 않으므로 통째로 담는다 —
 *                 어느 챕터인지는 세션이 알고, 그 값은 play 의 것이다 (§5.3)
 */
public record StoryBriefView(UUID storyVersionId, UUID storyId, String title, String coverImage,
		List<ChapterTitle> chapters) {

	public StoryBriefView {
		chapters = List.copyOf(chapters == null ? List.of() : chapters);
	}

	/** R13.2 — 진행률은 백분율이 아니라 이 값과 현재 챕터 번호로 표현된다. */
	public int totalChapters() {
		return this.chapters.size();
	}

	/** @return 그 번호의 챕터 제목. 없으면 비어 있다 — 정의가 사라진 버전일 수 있다 */
	public Optional<String> chapterTitle(int chapterNo) {
		return this.chapters.stream()
				.filter(chapter -> chapter.chapterNo() == chapterNo)
				.map(ChapterTitle::title)
				.findFirst();
	}

	public record ChapterTitle(int chapterNo, String title) {
	}
}

package com.neowadaeum.ai.provider;

import java.util.List;

/**
 * 챕터·엔딩 초안 (§3, B-18 시그니처 / B-52 구현).
 *
 * <p><b>조건식은 여기에 없다.</b> UGC 작성자는 조건을 자유 서술하지 않고 템플릿에서 고른다
 * (R7.16, R4.4). AI 가 조건을 만들어 내면 그것을 검증할 방법이 없다.
 *
 * @param chapterOutlines 챕터 초안 본문
 * @param endingOutlines  엔딩 초안 본문
 */
public record OutlineResult(List<String> chapterOutlines, List<String> endingOutlines) {

	public OutlineResult {
		chapterOutlines = List.copyOf(chapterOutlines == null ? List.of() : chapterOutlines);
		endingOutlines = List.copyOf(endingOutlines == null ? List.of() : endingOutlines);
	}
}

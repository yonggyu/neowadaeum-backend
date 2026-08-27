package com.neowadaeum.common.spi;

import java.util.List;

/**
 * 챕터·엔딩 초안 (R7.14, R7.16).
 *
 * <p><b>조건을 담지 않는다.</b> AI 가 조건식을 지어내면 작성자는 그것을 읽지도 고치지도 못한다 —
 * 조건은 <b>플랫폼이 제공하는 템플릿에서 고른다</b> (R7.16). 여기 오는 것은 사람이 읽는 것뿐이다.
 *
 * <p><b>기본 엔딩을 여기서 정하지 않는다.</b> 어느 것이 기본인가는 서버가 정한다 (§13-16) —
 * AI 제안값을 최종 권한으로 쓰지 않는다는 규칙(I-10)과 같은 성질이다.
 */
public record OutlineDraft(List<Chapter> chapters, List<Ending> endings) {

	public OutlineDraft {
		chapters = List.copyOf(chapters == null ? List.of() : chapters);
		endings = List.copyOf(endings == null ? List.of() : endings);
	}

	/**
	 * 챕터 하나.
	 *
	 * @param summarySeed 그 챕터에서 무슨 일이 일어나는지. 작성자가 고칠 씨앗이다
	 */
	public record Chapter(int chapterNo, String title, String summarySeed) {
	}

	/**
	 * 엔딩 하나.
	 *
	 * @param label 목록에 보이는 이름
	 * @param epilogueText 마지막에 붙는 글. 비어 있을 수 있다
	 */
	public record Ending(int endingNo, String label, String epilogueText) {
	}
}

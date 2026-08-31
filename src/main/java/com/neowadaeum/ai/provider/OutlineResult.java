package com.neowadaeum.ai.provider;

import java.util.List;

/**
 * 챕터·엔딩 초안 (§3, B-18 시그니처 / B-52 구현).
 *
 * <p><b>조건식은 여기에 없다.</b> UGC 작성자는 조건을 자유 서술하지 않고 템플릿에서 고른다
 * (R7.16, R4.4). AI 가 조건을 만들어 내면 그것을 검증할 방법이 없다.
 *
 * <p><b>번호가 없다.</b> {@code OutlineDraft} 와 이 타입을 가르는 것이 그 한 가지이며, 그것이
 * 이 타입의 존재 이유다 — 번호를 담을 자리가 없으면 모델이 매긴 번호가 흘러들 길도 없다
 * (R7.14: 번호와 구조는 서버가 붙인다). 붙이는 곳은 {@code ProviderOutlineDrafter} 하나다.
 *
 * <p><b>모자란 것은 어긋난 것이 아니다.</b> 요청한 개수보다 적게 와도 이 타입은 그대로 담는다 —
 * 부족한 자리를 빈 문장으로 채우면 작성자는 그것을 <b>AI 가 제안한 것</b>으로 읽는다. 개수는
 * 계약이 아니라 요청이며, 그래서 개수 부족만으로는 재요청하지 않는다 (#238).
 */
public record OutlineResult(List<Chapter> chapters, List<Ending> endings) {

	public OutlineResult {
		chapters = List.copyOf(chapters == null ? List.of() : chapters);
		endings = List.copyOf(endings == null ? List.of() : endings);
	}

	/**
	 * 챕터 초안 하나.
	 *
	 * @param title 목록에 보일 이름
	 * @param summarySeed 그 챕터에서 무슨 일이 일어나는지. 작성자가 고칠 씨앗이다
	 */
	public record Chapter(String title, String summarySeed) {
	}

	/**
	 * 엔딩 초안 하나.
	 *
	 * <p><b>어느 것이 기본 엔딩인지 담지 않는다</b> (§13-16, I-10). 서버가 판정한다.
	 *
	 * @param label 목록에 보일 이름
	 * @param epilogueText 마지막에 붙는 글. 없으면 {@code null} — 빈 문자열은 <b>비어 있는 글</b>로 읽힌다
	 */
	public record Ending(String label, String epilogueText) {
	}
}

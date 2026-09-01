package com.neowadaeum.ai.provider;

/**
 * UGC 작품의 챕터·엔딩 초안 요청 (§3, B-18 시그니처 / B-52 구현).
 *
 * <p><b>초안 결과도 검수 대상이다</b> (R7.15). 이 요청이 만든 것은 제안일 뿐이며 그대로 게시되지
 * 않는다.
 *
 * <p><b>필드를 지어내지 않는다.</b> 원문이 정하는 것은 "챕터·엔딩 초안"이라는 용도와 개수(B-52 의
 * 챕터 5 · 엔딩 3)까지다. 드래프트의 어떤 필드를 넘길지는 B-52 가 정하며, 여기서 미리 확정하면
 * 그 작업이 물려받아 정리해야 한다.
 *
 * @param worldPrompt  작품 레이어의 세계관 문구. 작성자가 쓴 것이며 플랫폼 레이어를 덮어쓰지 못한다 (I-7)
 * @param chapterCount 요청할 챕터 초안 개수
 * @param endingCount  요청할 엔딩 초안 개수
 */
public record OutlineRequest(String worldPrompt, int chapterCount, int endingCount) {

	public OutlineRequest {
		if (worldPrompt == null || worldPrompt.isBlank()) {
			throw new IllegalArgumentException("worldPrompt is required");
		}
		if (chapterCount <= 0 || endingCount <= 0) {
			throw new IllegalArgumentException("outline counts must be positive");
		}
	}
}

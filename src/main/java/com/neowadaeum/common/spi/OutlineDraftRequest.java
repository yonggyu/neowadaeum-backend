package com.neowadaeum.common.spi;

/**
 * 초안을 만들 재료 (R7.14).
 *
 * <p><b>회원 식별정보를 담지 않는다</b> (I-3). 담을 자리가 없는 것이 보장이다 — 필터링으로
 * 지우는 것이 아니라 필드를 만들지 않는다.
 *
 * <p><b>원고 id 도 담지 않는다.</b> 초안은 세계관에서 나오지 <b>누구의 원고인가</b>에서 나오지
 * 않는다 — 담으면 그 값이 프롬프트에 실려 갈 자리가 생긴다.
 *
 * @param worldPrompt 세계관 (§8.1 의 2단계)
 * @param chapterCount 만들 챕터 수
 * @param endingCount 만들 엔딩 수
 */
public record OutlineDraftRequest(String worldPrompt, int chapterCount, int endingCount) {

	public OutlineDraftRequest {
		if (worldPrompt == null || worldPrompt.isBlank()) {
			throw new IllegalArgumentException("worldPrompt is required");
		}
		if (chapterCount <= 0 || endingCount <= 0) {
			throw new IllegalArgumentException("outline counts must be positive");
		}
	}
}

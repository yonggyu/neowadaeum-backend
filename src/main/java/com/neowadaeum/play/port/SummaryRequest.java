package com.neowadaeum.play.port;

import java.util.List;

/**
 * 오래된 턴을 요약으로 압축하는 요청 (§4.2, B-18 시그니처 / B-34 구현).
 *
 * <p><b>I-3 — 회원 식별정보를 담을 자리가 없다.</b> {@link TurnRequest} 와 같은 설계다.
 *
 * <p><b>필드는 R4.5 · R4.7 이 규정한 것만 둔다.</b> 압축 전략과 재압축 조건은 B-34 이며, 여기서
 * 그것을 앞당겨 표현하지 않는다.
 *
 * @param previousSummary 직전 요약. 첫 압축이면 {@code null}
 * @param turns           요약에 병합할 턴들. 최근 8턴을 초과한 분량이다 (R4.5)
 * @param maxTokens       결과 요약의 토큰 상한. 초과하면 재압축한다 (R4.5)
 */
public record SummaryRequest(String previousSummary, List<TurnDigest> turns, int maxTokens) {

	public SummaryRequest {
		turns = List.copyOf(turns == null ? List.of() : turns);
		if (turns.isEmpty() && (previousSummary == null || previousSummary.isBlank())) {
			// 둘 다 없으면 압축할 것이 없다. 하나만 있는 것은 정상이다 — 재압축은 <b>요약만</b>
			// 다시 압축하는 일이고(R4.5), 병합할 새 턴이 없어도 예산은 넘을 수 있다.
			throw new IllegalArgumentException("nothing to summarize");
		}
		if (maxTokens <= 0) {
			throw new IllegalArgumentException("maxTokens must be positive");
		}
	}

	/**
	 * 요약에 넘기는 한 턴 (R4.7).
	 *
	 * @param turnNo           세션 내 턴 번호
	 * @param chosenChoiceText 그 턴에서 사용자가 고른 선택지의 본문. 마지막 턴이면 {@code null}
	 * @param paragraphsDigest 본문 요지
	 */
	public record TurnDigest(int turnNo, String chosenChoiceText, String paragraphsDigest) {

		public TurnDigest {
			if (turnNo <= 0) {
				throw new IllegalArgumentException("turnNo must be positive");
			}
			if (paragraphsDigest == null || paragraphsDigest.isBlank()) {
				throw new IllegalArgumentException("paragraphsDigest is required");
			}
		}
	}
}

package com.neowadaeum.play.orchestrator;

import java.util.Optional;

/**
 * 이번에 요약으로 옮길 턴 구간 (R4.5, §13-2, B-34).
 *
 * <p><b>계산만 하는 값이라 따로 두었다.</b> "언제 · 어디까지 옮기는가"는 DB 도 Provider 도 없이
 * 정해지는 규칙이고, 그것을 파이프라인 안에 두면 <b>확인하려면 컨테이너를 띄워야 한다.</b> 여기서는
 * 경계값을 직접 쓴다 (ADR-0001 의 분류와 같은 이유).
 *
 * @param from 이번에 옮길 첫 턴 (포함)
 * @param to   마지막 턴 (포함). 이 번호까지가 요약에 들어간다
 */
public record SummaryWindow(int from, int to) {

	/**
	 * 옮길 구간을 정한다. 옮길 것이 없으면 비어 있다.
	 *
	 * <p><b>완충 구간을 남긴다</b> (§13-2). {@code summaryMerge}(기본 8)보다 최근의 턴은 프롬프트에
	 * 직접 실리므로(R4.7) 요약이 중복해서 담지 않는다 — 요약 갱신이 비동기라 지연될 수 있고, 그
	 * 구간이 겹치는 완충지대다.
	 *
	 * <p><b>이미 요약된 구간은 다시 부르지 않는다.</b> 같은 구간을 두 번 압축하면 결과는 같고 비용만
	 * 두 배다.
	 *
	 * @param currentTurnNo   방금 저장된 턴 번호
	 * @param summarizedUpto  현재 요약이 포함하는 마지막 턴. 요약이 없으면 {@code 0}
	 * @param summaryMerge    완충 구간의 크기 (§13-2)
	 */
	public static Optional<SummaryWindow> of(int currentTurnNo, int summarizedUpto, int summaryMerge) {
		int mergeUpto = currentTurnNo - summaryMerge;
		if (mergeUpto < 1) {
			return Optional.empty();
		}

		int from = summarizedUpto + 1;
		if (from > mergeUpto) {
			return Optional.empty();
		}
		return Optional.of(new SummaryWindow(from, mergeUpto));
	}
}

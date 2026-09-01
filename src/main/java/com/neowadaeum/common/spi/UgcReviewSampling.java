package com.neowadaeum.common.spi;

/**
 * 승인 후 무작위 샘플링 검수 (R8.11, B-59, ADR-0003).
 *
 * <p><b>{@code unlisted} 는 인간 검수를 거치지 않았다</b> (R8.6). 링크는 무한히 확산되므로
 * 도달 범위로는 {@code public} 과 다르지 않고, 그 격차를 메우는 것이 사후 샘플링이다 —
 * <b>사전 검수가 약한 쪽을 사후에 더 본다.</b>
 *
 * <p><b>재스캔과 다른 일이다</b> ({@link UgcRescan}). 재스캔은 <b>걸린 것</b>을 찾아 내리고,
 * 샘플링은 <b>아무 근거 없이</b> 사람 눈에 올린다 — 그래서 샘플링은 작품을 내리지 않는다.
 *
 * <p><b>구현은 {@code authoring} 이다</b> — 검수 큐를 소유한 모듈이며, ADR-0003 이 <b>"실행
 * 결과 적재는 구현 모듈이 한다"</b> 고 정했다.
 *
 * @see UgcRescan
 */
public interface UgcReviewSampling {

	/**
	 * 승인작 중 일부를 뽑아 검수 큐에 올린다.
	 *
	 * @return 이번 회차에 새로 올린 작품 수. batch 가 구조화 로그에 남긴다 (§9.4)
	 */
	int sample();
}

package com.neowadaeum.common.spi;

/**
 * 이미 승인된 UGC 를 다시 본다 (R9.4, B-59, ADR-0003).
 *
 * <p><b>블록리스트는 운영 중에 늘어난다.</b> 어제 통과한 작품이 오늘의 목록으로는 걸리며,
 * 갱신이 <b>앞으로 만들어질 것</b>에만 적용되면 이미 게시된 것은 영원히 옛 기준으로 남는다.
 *
 * <p><b>구현은 {@code authoring} 이다</b> — 블록리스트를 소유하고 검수 이력을 적재하는
 * 모듈이며, ADR-0003 이 <b>"실행 결과 적재는 구현 모듈이 한다"</b> 고 정했다. {@code batch} 가
 * 적재하면 {@code batch → authoring} 의존이 생겨 {@code admin → batch} 와 만나 순환이 된다.
 *
 * <p><b>{@code batch} 는 이 메서드 하나만 부른다.</b> 무엇을 어떻게 보는지는 구현의 일이고,
 * batch 가 아는 것은 <b>언제 부르는가</b>뿐이다.
 *
 * @see EndingStatAggregation
 */
public interface UgcRescan {

	/**
	 * 승인된 작품을 한 차례 다시 본다.
	 *
	 * @return 이번 회차에 <b>새로 내려간</b> 작품 수. batch 가 구조화 로그에 남긴다 (§9.4)
	 */
	int rescan();
}

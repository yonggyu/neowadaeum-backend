package com.neowadaeum.common.spi;

/**
 * 엔딩 도달률 집계 한 회 (R2.7, I-20, B-39, ADR-0003).
 *
 * <p><b>구현은 {@code catalog} 다</b> — 통계 표를 소유한 모듈이며, ADR-0003 이 <b>"실행 결과
 * 적재는 구현 모듈이 한다"</b> 고 정했다. {@code batch} 가 적재하면 {@code batch → catalog}
 * 의존이 생겨 경계가 무너진다.
 *
 * <p><b>{@code batch} 는 이 메서드 하나만 부른다.</b> 어디서 읽어 어디에 쓰는지는 구현의 일이고,
 * batch 가 아는 것은 <b>언제 부르는가</b>뿐이다.
 *
 * @see EndingReachSource
 */
public interface EndingStatAggregation {

	/**
	 * 집계를 한 번 돌린다.
	 *
	 * @return 갱신된 행 수. batch 가 구조화 로그에 남긴다 (§9.4)
	 */
	int refresh();
}

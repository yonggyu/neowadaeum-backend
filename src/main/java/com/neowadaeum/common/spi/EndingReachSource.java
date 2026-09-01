package com.neowadaeum.common.spi;

import java.util.List;

/**
 * 완주 세션의 도달 집계를 내놓는다 (B-39, ADR-0003).
 *
 * <p><b>구현은 {@code play} 다</b> — 완주 세션을 소유한 모듈이다. {@code batch} 는 이 인터페이스만
 * 알고 {@code play} 를 참조하지 않는다.
 *
 * <p><b>지운 세션과 완주하지 않은 세션은 세지 않는다.</b> 전자는 사용자가 없앤 것이고 후자는
 * 아직 결과가 아니다.
 */
public interface EndingReachSource {

	/**
	 * @return 작품 · 엔딩별 도달 수. 완주 세션이 없으면 빈 목록이다
	 */
	List<EndingReach> tallyReached();
}

package com.neowadaeum.common.spi;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * 진행 중 세션이 <b>고정하고 있는</b> 작품 버전 (I-4, §13-80).
 *
 * <p><b>세션은 생성 시 버전에 고정된다</b> (I-4). 그래서 새 버전이 현재가 된 뒤에도 그 전에
 * 시작한 세션들은 <b>옛 버전의 세계관과 인물 페르소나를 매 턴 모델에 싣는다</b> — 사후 검수가
 * 현재 버전만 보면 <b>읽히고 있는데 검사되지 않는 자리</b>가 남는다 (R8.11).
 *
 * <p><b>구현은 {@code play} 다</b> — 세션을 소유한 모듈이며 ADR-0003 이 정한 자리다.
 * {@code catalog} 는 이 인터페이스만 알고 {@code play} 를 참조하지 않는다. 스키마가 다르므로
 * JOIN 으로 물을 수도 없다 (§5.3).
 *
 * @see UgcRescan
 */
public interface PinnedStoryVersions {

	/**
	 * 이 작품들에 대해 진행 중 세션이 붙들고 있는 버전 id.
	 *
	 * <p><b>이어갈 수 있는 세션만이다.</b> 끝났거나 버려졌거나 만료된 세션은 다음 턴을 만들지
	 * 않으므로 그 버전을 더 읽지 않는다.
	 *
	 * @param storyIds 물어볼 작품. 비어 있으면 빈 집합이다
	 * @return 버전 id. <b>현재 버전이 섞여 있을 수 있다</b> — 어느 것이 현재인지는 묻는 쪽의
	 *     지식이므로 여기서 가려내지 않는다
	 */
	Set<UUID> pinnedVersionsOf(Collection<UUID> storyIds);
}

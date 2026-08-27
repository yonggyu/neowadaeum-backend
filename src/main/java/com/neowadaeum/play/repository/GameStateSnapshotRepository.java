package com.neowadaeum.play.repository;

import com.neowadaeum.play.domain.GameStateSnapshot;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 스냅샷 영속화 (§9.2).
 *
 * <p><b>I-5 — append-only.</b> {@link JpaRepository#save} 는 새 스냅샷을 추가하는 데만 쓴다.
 * 기존 행을 불러 고쳐 저장하는 경로를 만들지 않는다 — 그 순간 롤백할 대상이 사라진다.
 */
public interface GameStateSnapshotRepository extends JpaRepository<GameStateSnapshot, UUID> {

	/**
	 * 가장 최근의 살아 있는 스냅샷. 다음 턴의 병합 기준이다 (§4.3-8).
	 *
	 * <p>되돌려진 스냅샷은 제외한다 — 롤백은 soft delete 이므로 행은 남아 있다 (§13-9, I-5).
	 */
	java.util.Optional<GameStateSnapshot> findFirstBySessionIdAndDeletedAtIsNullOrderByTurnNoDesc(UUID sessionId);

	/**
	 * 되돌리기가 접을 대상 (R14.4, B-42).
	 *
	 * <p><b>요약과 같은 기준으로 자른다.</b> 기준이 어긋나면 상태와 이야기가 다른 지점을
	 * 가리킨 채 다음 턴이 만들어진다.
	 */
	java.util.List<GameStateSnapshot> findBySessionIdAndTurnNoGreaterThanAndDeletedAtIsNull(
			UUID sessionId, int turnNo);
}

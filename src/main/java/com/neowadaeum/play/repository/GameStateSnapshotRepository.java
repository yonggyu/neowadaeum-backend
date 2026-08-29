package com.neowadaeum.play.repository;

import com.neowadaeum.play.domain.GameStateSnapshot;
import java.util.UUID;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

	/**
	 * 탈퇴 회원의 기록 파기 (R12.4, B-61).
	 *
	 * <p>스냅샷은 append-only 다 (I-5). <b>그 규칙은 진행 중 기록을 덮어쓰지 말라는 뜻이지 파기하지 말라는 뜻이 아니다</b> — 되돌릴 대상이 사라진 뒤의 append-only 는 보관 기간을 어기는 이름이 된다.
	 *
	 * <p><b>벌크 삭제다.</b> 엔티티를 읽어 지우면 그 회원의 플레이 전체를 메모리에 올리게 된다 —
	 * 지우는 일에 필요한 것은 <b>조건</b>이지 행의 내용이 아니다.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			DELETE FROM GameStateSnapshot g
			WHERE g.sessionId IN (SELECT s.id FROM PlaySession s WHERE s.playerRef IN :playerRefs)
			""")
	int deleteByPlayerRefs(@Param("playerRefs") Collection<UUID> playerRefs);
}

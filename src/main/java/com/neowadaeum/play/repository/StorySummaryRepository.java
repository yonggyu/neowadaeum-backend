package com.neowadaeum.play.repository;

import com.neowadaeum.play.domain.StorySummary;
import java.util.Optional;
import java.util.UUID;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 요약 영속화 (§4.2, B-34).
 *
 * <p><b>R2.6 — append-only.</b> {@code save} 는 새 요약을 추가하는 데만 쓴다. 기존 행을 불러 고쳐
 * 저장하는 경로를 만들지 않는다 — 그 순간 롤백(R14.4)이 되돌릴 대상을 잃는다.
 */
public interface StorySummaryRepository extends JpaRepository<StorySummary, UUID> {

	/**
	 * 현재 요약 — 살아 있는 행 중 가장 최근의 것.
	 *
	 * <p><b>{@code uptoTurnNo} 가 먼저이고 {@code createdAt} 이 그다음이다.</b> 재압축이 같은
	 * {@code uptoTurnNo} 로 새 행을 남기므로(R4.5), 그 경우의 승자는 나중에 쓰인 쪽이다.
	 *
	 * <p>되돌려진 요약은 제외한다 — 롤백은 soft delete 이므로 행은 남아 있다 (§13-9).
	 */
	Optional<StorySummary> findFirstBySessionIdAndDeletedAtIsNullOrderByUptoTurnNoDescCreatedAtDesc(UUID sessionId);

	/**
	 * 되돌리기가 접을 대상 (R14.4, B-42).
	 *
	 * <p><b>스냅샷과 같은 기준으로 자른다.</b> 요약만 남으면 상태가 어긋난다 — R14.4 가
	 * "함께"라고 적은 것이 이것이다.
	 */
	java.util.List<StorySummary> findBySessionIdAndUptoTurnNoGreaterThanAndDeletedAtIsNull(
			UUID sessionId, int uptoTurnNo);

	/**
	 * 탈퇴 회원의 기록 파기 (R12.4, B-61).
	 *
	 * <p>요약은 세션의 내용을 압축한 것이다 — 세션이 사라지면 압축해 둘 원본도 없다.
	 *
	 * <p><b>벌크 삭제다.</b> 엔티티를 읽어 지우면 그 회원의 플레이 전체를 메모리에 올리게 된다 —
	 * 지우는 일에 필요한 것은 <b>조건</b>이지 행의 내용이 아니다.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			DELETE FROM StorySummary m
			WHERE m.sessionId IN (SELECT s.id FROM PlaySession s WHERE s.playerRef IN :playerRefs)
			""")
	int deleteByPlayerRefs(@Param("playerRefs") Collection<UUID> playerRefs);
}

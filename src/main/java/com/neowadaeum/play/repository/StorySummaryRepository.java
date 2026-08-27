package com.neowadaeum.play.repository;

import com.neowadaeum.play.domain.StorySummary;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

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
}

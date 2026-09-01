package com.neowadaeum.ai.log;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 관리자 감사 영속화 (R14.5).
 *
 * <p><b>append-only 다.</b> 갱신·삭제 메서드를 두지 않는다 — 파기는 보관 주기(3년, S-10)를
 * 지키는 배치의 몫이며 그것도 B-61 이다.
 */
public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, UUID> {

	List<AdminAuditLog> findByAdminUserIdOrderByCreatedAtDesc(UUID adminUserId, Limit limit);

	/**
	 * 보관 기간이 지난 행을 지운다 (B-61).
	 *
	 * <p><b>벌크다.</b> 엔티티를 읽어 지우면 기간치를 전부 메모리에 올리게 된다 — 지우는 일에
	 * 필요한 것은 <b>조건</b>이지 행의 내용이 아니다.
	 */
	@org.springframework.data.jpa.repository.Modifying
	@org.springframework.data.jpa.repository.Query(
			"DELETE FROM AdminAuditLog l WHERE l.createdAt < :before")
	int deleteCreatedBefore(java.time.Instant before);
}

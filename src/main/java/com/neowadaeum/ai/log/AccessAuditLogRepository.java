package com.neowadaeum.ai.log;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@code access_audit_log} 영속화 (R12.3, S-5). {@code promptlog} 스토어 전용이다. */
public interface AccessAuditLogRepository extends JpaRepository<AccessAuditLog, UUID> {

	/** 무엇이 읽혔는지 되짚는다. 사고 조사가 이 방향으로 본다. */
	List<AccessAuditLog> findByResourceAndResourceIdOrderByCreatedAtDesc(String resource,
			UUID resourceId, Limit limit);

	/** 누가 읽었는지 되짚는다. */
	List<AccessAuditLog> findByAdminUserIdOrderByCreatedAtDesc(UUID adminUserId, Limit limit);

	/**
	 * 보관 기간이 지난 행을 지운다 (B-61).
	 *
	 * <p><b>벌크다.</b> 엔티티를 읽어 지우면 기간치를 전부 메모리에 올리게 된다 — 지우는 일에
	 * 필요한 것은 <b>조건</b>이지 행의 내용이 아니다.
	 */
	@org.springframework.data.jpa.repository.Modifying
	@org.springframework.data.jpa.repository.Query(
			"DELETE FROM AccessAuditLog l WHERE l.createdAt < :before")
	int deleteCreatedBefore(java.time.Instant before);
}

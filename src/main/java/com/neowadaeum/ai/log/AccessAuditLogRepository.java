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
}

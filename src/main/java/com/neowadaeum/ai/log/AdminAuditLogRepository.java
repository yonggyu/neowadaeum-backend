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
}

package com.neowadaeum.ai.log;

import com.neowadaeum.common.spi.AuditedResource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 원문을 읽은 사실 (R12.3, S-5, §2.7).
 *
 * <p><b>{@code ai_call_log} 를 읽는 것 자체가 기록 대상이다.</b> 원문이 여기에만 있기 때문이다 —
 * 그 표를 열어 본 사람이 남지 않으면, 유출이 일어나도 <b>어디서 새어 나갔는지 알 수 없다.</b>
 *
 * <p><b>세터가 없다.</b> 저장 뒤에 고칠 수 있으면 이 표는 감사 기록이기를 그만둔다.
 */
@Entity
@Table(name = "access_audit_log")
public class AccessAuditLog {

	@Id
	@GeneratedValue
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "admin_user_id", nullable = false, updatable = false)
	private UUID adminUserId;

	@Column(name = "resource", nullable = false, updatable = false)
	private String resource;

	@Column(name = "resource_id", nullable = false, updatable = false)
	private UUID resourceId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected AccessAuditLog() {
	}

	static AccessAuditLog of(UUID adminUserId, AuditedResource resource, UUID resourceId, Instant now) {
		AccessAuditLog log = new AccessAuditLog();
		log.adminUserId = adminUserId;
		log.resource = resource.columnValue();
		log.resourceId = resourceId;
		log.createdAt = now;
		return log;
	}

	public UUID getId() {
		return this.id;
	}

	public UUID getAdminUserId() {
		return this.adminUserId;
	}

	public String getResource() {
		return this.resource;
	}

	public UUID getResourceId() {
		return this.resourceId;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}
}

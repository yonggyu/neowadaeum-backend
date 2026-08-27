package com.neowadaeum.ai.log;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 관리자 행위 한 건 (R14.5, §2.7).
 *
 * <p><b>append-only 다.</b> 세터가 없고 갱신 경로도 없다 — 감사 기록을 나중에 고칠 수 있으면
 * 그것은 감사 기록이 아니다.
 *
 * <p><b>{@code admin_user_id} 는 {@code user.id} 다.</b> 관리자 감사는 <b>사람</b>을 가리켜야
 * 하므로 {@code playerRef} 로 익명화하지 않는다 — I-3 이 지키려는 것은 <b>플레이 데이터가 사람과
 * 이어지지 않는 것</b>이고, 감사 기록은 정반대의 목적을 갖는다.
 *
 * <p><b>스키마 간 FK 가 없다</b> (§5.3). {@code admin_user_id} 는 identity 의 값이다.
 */
@Entity
@Table(name = "admin_audit_log")
public class AdminAuditLog {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "admin_user_id", nullable = false, updatable = false)
	private UUID adminUserId;

	@Column(name = "action", nullable = false, updatable = false)
	private String action;

	@Column(name = "target_type", nullable = false, updatable = false)
	private String targetType;

	@Column(name = "target_id", updatable = false)
	private UUID targetId;

	/** 맥락. <b>원문·토큰·이메일을 담지 않는다</b> (S-3). */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "payload", nullable = false, updatable = false)
	private String payload;

	/** IP 원문을 두지 않는다. 같은 접속자인지 비교하는 데는 해시로 충분하다 (§12). */
	@Column(name = "ip_hash", updatable = false)
	private String ipHash;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected AdminAuditLog() {
	}

	public static AdminAuditLog record(UUID adminUserId, String action, String targetType, UUID targetId,
			String payload, String ipHash, Instant now) {
		if (adminUserId == null || action == null || action.isBlank() || targetType == null) {
			throw new IllegalArgumentException("adminUserId, action, targetType are required");
		}
		AdminAuditLog log = new AdminAuditLog();
		log.adminUserId = adminUserId;
		log.action = action;
		log.targetType = targetType;
		log.targetId = targetId;
		log.payload = (payload != null) ? payload : "{}";
		log.ipHash = ipHash;
		log.createdAt = now;
		return log;
	}

	public UUID getAdminUserId() {
		return this.adminUserId;
	}

	public String getAction() {
		return this.action;
	}

	public String getTargetType() {
		return this.targetType;
	}

	public UUID getTargetId() {
		return this.targetId;
	}

	public String getPayload() {
		return this.payload;
	}

	public String getIpHash() {
		return this.ipHash;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}
}

package com.neowadaeum.authoring.report;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * 신고 한 건 (§2.4, R8.9, L3).
 *
 * <p><b>같은 사람이 같은 대상을 두 번 신고해도 한 건이다</b> — DB 의
 * {@code UNIQUE(reporter_ref, target_type, target_id)} 가 그 보장이다. 누적이 자동 정지의
 * 근거이므로, 중복이 세어지면 <b>한 사람이 혼자 작품을 내릴 수 있다.</b>
 *
 * <p><b>{@code detail} 은 사용자가 쓴 문장이다.</b> 응답에 되돌려주지 않는다 — 신고 내용이
 * 작성자에게 가면 신고자가 특정된다.
 */
@Entity
@Table(name = "content_report")
public class ContentReport {

	@Id
	@GeneratedValue
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "reporter_ref", nullable = false, updatable = false)
	private UUID reporterRef;

	@Column(name = "target_type", nullable = false, updatable = false)
	private String targetType;

	@Column(name = "target_id", nullable = false, updatable = false)
	private UUID targetId;

	/** 턴 신고는 어느 플레이에서 나왔는지가 있어야 재현된다. 작품 신고에는 없다. */
	@Column(name = "session_id", updatable = false)
	private UUID sessionId;

	@Column(name = "turn_no", updatable = false)
	private Integer turnNo;

	@Column(name = "reason", nullable = false, updatable = false)
	private String reason;

	@Column(name = "detail", updatable = false)
	private String detail;

	@Column(name = "status", nullable = false)
	private String status;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected ContentReport() {
	}

	public static ContentReport of(UUID reporterRef, ReportTarget target, UUID targetId,
			UUID sessionId, Integer turnNo, ReportReason reason, String detail, Instant now) {
		ContentReport report = new ContentReport();
		report.reporterRef = reporterRef;
		report.targetType = target.columnValue();
		report.targetId = targetId;
		report.sessionId = sessionId;
		report.turnNo = turnNo;
		report.reason = reason.columnValue();
		report.detail = detail;
		report.status = ReportStatus.OPEN.columnValue();
		report.createdAt = now;
		return report;
	}

	public UUID getId() {
		return this.id;
	}

	public UUID getTargetId() {
		return this.targetId;
	}

	public ReportTarget getTarget() {
		return ReportTarget.valueOf(this.targetType.toUpperCase(Locale.ROOT));
	}

	/** 왜 신고했는가. 검수자가 <b>무엇이 몇 건인지</b>를 보는 축이다 (§13-62). */
	public ReportReason getReason() {
		return ReportReason.valueOf(this.reason.toUpperCase(Locale.ROOT));
	}

	/** 어느 턴이 신고됐는가. 작품 신고에는 없다 — {@code null} 이 정상이다. */
	public Integer getTurnNo() {
		return this.turnNo;
	}

	// **reporter_ref 와 detail 에는 게터가 없다** (I-3, §13-62). 응답 DTO 가 실수로
	// 담을 수 있는 자리를 만들지 않는 것이 그 보장이며, 없는 게터는 잊혀도 새지 않는다.

	public ReportStatus getStatus() {
		return ReportStatus.valueOf(this.status.toUpperCase(Locale.ROOT));
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	/** 신고 처리 상태 (§2.4). */
	public enum ReportStatus {

		OPEN,

		REVIEWING,

		ACTIONED,

		DISMISSED;

		public String columnValue() {
			return name().toLowerCase(Locale.ROOT);
		}
	}
}

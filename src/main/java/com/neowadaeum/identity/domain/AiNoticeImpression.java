package com.neowadaeum.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * AI 사전 고지를 보여 준 사실 (§2.7, R11.3).
 *
 * <p><b>{@link ConsentLog} 와 분리된 표다</b> (§13-8). R11.3 은 노출 이력을 {@code consent_log} 에
 * 넣으라고 하지만, 노출은 동의가 아니라 <b>표시 사실</b>이다 — 섞으면 동의 이력의 증빙력이 흐려진다.
 *
 * <p>고지 문구는 코드가 아니라 {@code service_config} 에 있고 (R11.1), {@code noticeVersion} 이
 * "그때 무엇을 보여 줬는가"를 그 설정과 이어 준다. 문구를 코드에 하드코딩하면 이 값이 가리킬
 * 대상이 사라진다.
 *
 * <p>append-only 다. 보여 준 사실은 나중에 바뀌지 않는다.
 */
@Entity
@Table(name = "ai_notice_impression")
public class AiNoticeImpression {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	/** {@code service_config} 의 고지 문구 판본 (R11.1). */
	@Column(name = "notice_version", nullable = false, updatable = false)
	private String noticeVersion;

	@Column(name = "surface", nullable = false, updatable = false)
	private NoticeSurface surface;

	@Column(name = "shown_at", nullable = false, updatable = false)
	private Instant shownAt;

	protected AiNoticeImpression() {
	}

	public static AiNoticeImpression shown(UUID userId, String noticeVersion, NoticeSurface surface,
			Instant now) {
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		if (noticeVersion == null || noticeVersion.isBlank()) {
			throw new IllegalArgumentException("noticeVersion is required");
		}
		if (surface == null) {
			throw new IllegalArgumentException("surface is required");
		}
		AiNoticeImpression impression = new AiNoticeImpression();
		impression.userId = userId;
		impression.noticeVersion = noticeVersion;
		impression.surface = surface;
		impression.shownAt = now;
		return impression;
	}

	public UUID getId() {
		return this.id;
	}

	public UUID getUserId() {
		return this.userId;
	}

	public String getNoticeVersion() {
		return this.noticeVersion;
	}

	public NoticeSurface getSurface() {
		return this.surface;
	}

	public Instant getShownAt() {
		return this.shownAt;
	}
}

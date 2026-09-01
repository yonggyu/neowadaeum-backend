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
 * 동의 한 건 (§2.2, R10.2).
 *
 * <p><b>append-only 다.</b> 세터가 없다 — 동의는 시점의 사실이고, 저장한 뒤 고칠 수 있게 만드는
 * 순간 이 표는 법적 증빙이기를 그만둔다. 철회나 재동의는 UPDATE 가 아니라 <b>새 행</b>이다.
 *
 * <p><b>{@code version} 이 핵심이다.</b> 약관이 개정되면 같은 {@code consentType} 을 다시 받아야
 * 하고, "어느 판본에 동의했는가"가 증빙의 실질이다.
 *
 * <p>{@link ConsentType#AI_NOTICE} 는 <b>동의</b>만 뜻한다. 고지를 화면에 보여 준 사실은
 * {@link AiNoticeImpression} 이 따로 남긴다 (§13-8).
 */
@Entity
@Table(name = "consent_log")
public class ConsentLog {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(name = "consent_type", nullable = false, updatable = false)
	private ConsentType consentType;

	/** 동의한 약관·방침의 판본. 개정 시 재동의를 판정하는 기준이다. */
	@Column(name = "version", nullable = false, updatable = false)
	private String version;

	@Column(name = "agreed_at", nullable = false, updatable = false)
	private Instant agreedAt;

	/** IP 원문을 두지 않는다. 같은 접속자인지 비교하는 데는 해시로 충분하다 (§12). */
	@Column(name = "ip_hash", updatable = false)
	private String ipHash;

	protected ConsentLog() {
	}

	public static ConsentLog agree(UUID userId, ConsentType consentType, String version,
			String ipHash, Instant now) {
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		if (consentType == null) {
			throw new IllegalArgumentException("consentType is required");
		}
		if (version == null || version.isBlank()) {
			// 판본 없는 동의는 증빙이 되지 못한다.
			throw new IllegalArgumentException("version is required");
		}
		ConsentLog log = new ConsentLog();
		log.userId = userId;
		log.consentType = consentType;
		log.version = version;
		log.ipHash = ipHash;
		log.agreedAt = now;
		return log;
	}

	public UUID getId() {
		return this.id;
	}

	public UUID getUserId() {
		return this.userId;
	}

	public ConsentType getConsentType() {
		return this.consentType;
	}

	public String getVersion() {
		return this.version;
	}

	public Instant getAgreedAt() {
		return this.agreedAt;
	}

	public String getIpHash() {
		return this.ipHash;
	}
}

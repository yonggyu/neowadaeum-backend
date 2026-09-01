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
 * 소셜 계정과 회원의 연결 (§2.2).
 *
 * <p><b>이메일 원문을 담지 않는다.</b> {@code emailHash} 만 둔다 — 같은 사람인지 비교하는 데는
 * 해시로 충분하고, 원문이 없으면 AI 페이로드로 새어 나갈 값 자체가 존재하지 않는다 (I-3).
 *
 * <p><b>{@code (provider, subject)} 가 UNIQUE 다.</b> 같은 구글 계정이 두 회원에 붙으면 로그인이
 * 어느 쪽으로도 갈 수 있다 — 그 상태를 DB 가 거부한다.
 */
@Entity
@Table(name = "oauth_identity")
public class OauthIdentity {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	/** identity 스키마 안이므로 FK 가 걸려 있다 (§5.3, R2.9). */
	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(name = "provider", nullable = false, updatable = false)
	private OauthProvider provider;

	/** provider 가 발급한 계정 식별자({@code sub}). provider 안에서만 유일하다. */
	@Column(name = "subject", nullable = false, updatable = false)
	private String subject;

	/** 이메일 원문이 아니라 해시다 (§12). 제공되지 않으면 {@code null} 이다. */
	@Column(name = "email_hash")
	private String emailHash;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected OauthIdentity() {
	}

	public static OauthIdentity link(UUID userId, OauthProvider provider, String subject,
			String emailHash, Instant now) {
		if (userId == null || provider == null || subject == null || subject.isBlank()) {
			throw new IllegalArgumentException("userId, provider, subject are required");
		}
		OauthIdentity identity = new OauthIdentity();
		identity.userId = userId;
		identity.provider = provider;
		identity.subject = subject;
		identity.emailHash = emailHash;
		identity.createdAt = now;
		return identity;
	}

	public UUID getId() {
		return this.id;
	}

	public UUID getUserId() {
		return this.userId;
	}

	public OauthProvider getProvider() {
		return this.provider;
	}

	public String getSubject() {
		return this.subject;
	}

	public String getEmailHash() {
		return this.emailHash;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}
}

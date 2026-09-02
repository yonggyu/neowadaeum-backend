package com.neowadaeum.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * UGC 작성자의 공개 표시명 (§13-7).
 *
 * <p><b>왜 catalog 에 있는가.</b> §13.3 은 {@code authorDisplayName} 을 반환하지만 catalog 는
 * {@code playerRef} 만 알고, 스토어 분리 원칙상 identity 를 조회할 수도 없다. 닉네임은 회원
 * 식별정보가 아니라 <b>공개 표시명</b>이므로 여기 두는 것이 타당하다. 설정 시 identity 가
 * catalog 파사드로 동기화한다.
 *
 * <p><b>I-3 — {@code user.id} 를 담을 컬럼이 없다.</b> PK 가 {@code playerRef} 이며, 그 값만으로는
 * 사람을 특정할 수 없다.
 */
@Entity
@Table(name = "author_profile")
public class AuthorProfile {

	@Id
	@Column(name = "player_ref", nullable = false, updatable = false)
	private UUID playerRef;

	@Column(name = "display_name", nullable = false)
	private String displayName;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected AuthorProfile() {
	}

	/**
	 * @throws IllegalArgumentException 표시명이 규칙에 맞지 않는다 (#287) — {@link DisplayNames}
	 */
	public static AuthorProfile of(UUID playerRef, String displayName, Instant now) {
		if (playerRef == null) {
			throw new IllegalArgumentException("playerRef is required");
		}
		AuthorProfile profile = new AuthorProfile();
		profile.playerRef = playerRef;
		profile.displayName = DisplayNames.normalize(displayName);
		profile.updatedAt = now;
		return profile;
	}

	/**
	 * 표시명을 바꾼다. identity 가 닉네임을 갱신할 때 동기화되는 유일한 경로다.
	 *
	 * <p><b>생성과 같은 규칙을 받는다</b> (#287). 한쪽만 검증하면 <b>바꾸는 길로 규칙을
	 * 우회</b>할 수 있다.
	 *
	 * @throws IllegalArgumentException 표시명이 규칙에 맞지 않는다 — {@link DisplayNames}
	 */
	public void rename(String displayName, Instant now) {
		this.displayName = DisplayNames.normalize(displayName);
		this.updatedAt = now;
	}

	public UUID getPlayerRef() {
		return this.playerRef;
	}

	public String getDisplayName() {
		return this.displayName;
	}

	public Instant getUpdatedAt() {
		return this.updatedAt;
	}
}

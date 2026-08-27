package com.neowadaeum.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 관리자의 두 번째 인증 요소 (B-40, R14.6, S-4).
 *
 * <p><b>비밀은 암호문으로만 여기 있다.</b> 엔티티는 그것을 해독하지 않는다 — 평문을 다루는 곳이
 * 늘어날수록 로그·예외·디버거에 새어 나갈 자리가 늘어난다 (S-3).
 *
 * <p><b>확인되기 전의 비밀은 문을 열지 못한다.</b> {@code confirmedAt} 이 비어 있으면 등록이
 * 끝나지 않은 것이다 — 인증기에 제대로 들어갔는지 확인하지 않은 채로 통과시키면, 관리자가
 * 스스로 잠기거나 반대로 잘못 등록된 비밀이 유효해진다.
 */
@Entity
@Table(name = "admin_totp")
public class AdminTotp {

	@Id
	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(name = "secret_enc", nullable = false)
	private String secretEnc;

	@Column(name = "confirmed_at")
	private Instant confirmedAt;

	/**
	 * 마지막으로 통과한 시간 스텝.
	 *
	 * <p><b>같은 코드를 두 번 통과시키지 않기 위한 값이다.</b> 코드는 30초 동안 같으므로, 이것이
	 * 없으면 한 번 노출된 여섯 자리가 그 창 안에서 몇 번이든 쓰인다.
	 */
	@Column(name = "last_used_step")
	private Long lastUsedStep;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected AdminTotp() {
	}

	private AdminTotp(UUID userId, String secretEnc, Instant now) {
		this.userId = userId;
		this.secretEnc = secretEnc;
		this.createdAt = now;
	}

	/** 새 비밀을 등록한다. <b>확인 전 상태로 시작한다.</b> */
	public static AdminTotp enroll(UUID userId, String secretEnc, Instant now) {
		return new AdminTotp(userId, secretEnc, now);
	}

	/**
	 * 비밀을 갈아 끼운다.
	 *
	 * <p><b>확인 여부와 재사용 기록을 함께 되돌린다.</b> 새 비밀에 옛 확인이 남으면 확인하지 않은
	 * 비밀이 곧바로 유효해지고, 옛 스텝이 남으면 새 인증기의 첫 코드가 <b>이미 쓴 것</b>으로 몰린다.
	 */
	public void replaceSecret(String secretEnc, Instant now) {
		this.secretEnc = secretEnc;
		this.confirmedAt = null;
		this.lastUsedStep = null;
		this.createdAt = now;
	}

	/** 등록을 확정한다. 이 시점부터 이 비밀이 문을 연다. */
	public void confirm(long usedStep, Instant now) {
		this.confirmedAt = now;
		this.lastUsedStep = usedStep;
	}

	/** 통과한 스텝을 기록한다. 이후 검증은 이 값보다 뒤의 스텝만 받는다. */
	public void markUsed(long usedStep) {
		this.lastUsedStep = usedStep;
	}

	public boolean isConfirmed() {
		return this.confirmedAt != null;
	}

	/** <b>이미 쓴 스텝인가.</b> 기록이 없으면 어떤 스텝도 처음이다. */
	public boolean hasUsed(long step) {
		return this.lastUsedStep != null && step <= this.lastUsedStep;
	}

	public UUID getUserId() {
		return this.userId;
	}

	public String getSecretEnc() {
		return this.secretEnc;
	}

	public Instant getConfirmedAt() {
		return this.confirmedAt;
	}

	public Long getLastUsedStep() {
		return this.lastUsedStep;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}
}

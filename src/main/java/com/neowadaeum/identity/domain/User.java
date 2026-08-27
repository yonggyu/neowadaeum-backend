package com.neowadaeum.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 회원 (§2.2).
 *
 * <p><b>이 엔티티가 identity 스토어의 경계다.</b> {@code id} 는 identity 밖으로 나가지 않는다 —
 * 다른 스토어가 회원을 가리킬 때 쓰는 값은 {@link #getPlayerRef() playerRef} 뿐이며, 그 값은
 * 회원정보와 아무 관계가 없는 UUID 다 (§2.1, I-3).
 *
 * <p><b>나이를 캐시하지 않는다</b> (§2.2). {@code birthDate} 원본만 두고 파생값을 컬럼으로 만들지
 * 않는다 — 생일이 지나면 나이가 바뀌므로 캐시는 반드시 틀린 날이 온다. 만 15세 판정은 B-13 이
 * 이 값과 현재 시각으로 그때그때 계산한다.
 *
 * <p><b>이메일·이름을 담지 않는다.</b> 소셜 계정 식별자와 이메일 해시는 {@link OauthIdentity} 에
 * 있고 원문은 어디에도 없다 (§13-11).
 *
 * <p>상태 전이(정지·탈퇴)와 연령 확인 기록은 B-40 · B-61 · B-13 의 범위이고 여기에는 아직 없다.
 * 지금 필요한 것은 <b>가입 시점의 생성</b>뿐이다.
 */
@Entity
@Table(name = "\"user\"")
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	/**
	 * 회원당 1개의 UUID. 비-Identity 스토어가 사람을 가리키는 유일한 값이다 (§2.1, I-3).
	 *
	 * <p>DB 의 UNIQUE 제약이 "회원당 1개"를, {@code updatable = false} 가 발급 후 바뀌지 않음을
	 * 강제한다 — 바뀌면 play·catalog 의 기록이 통째로 주인을 잃는다.
	 */
	@Column(name = "player_ref", nullable = false, updatable = false)
	private UUID playerRef;

	@Column(name = "status", nullable = false)
	private UserStatus status;

	/** 가입 연령 게이트(B-13)가 쓰는 원본. 미확인 회원은 {@code null} 이다. */
	@Column(name = "birth_date")
	private LocalDate birthDate;

	/** 연령 확인을 마친 시각 (R10.2). 확인 전에는 {@code null} 이다. */
	@Column(name = "age_verified_at")
	private Instant ageVerifiedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected User() {
	}

	/**
	 * 새 회원을 만든다.
	 *
	 * <p>{@code playerRef} 를 인자로 받는 것은 의도다 — 생성 시점에 확정돼야 하고, 엔티티가
	 * 스스로 만들면 그 값이 어디서 왔는지 호출부에서 보이지 않는다.
	 */
	public static User register(UUID playerRef, LocalDate birthDate, Instant now) {
		if (playerRef == null) {
			throw new IllegalArgumentException("playerRef is required");
		}
		User user = new User();
		user.playerRef = playerRef;
		user.status = UserStatus.ACTIVE;
		user.birthDate = birthDate;
		user.createdAt = now;
		return user;
	}

	public UUID getId() {
		return this.id;
	}

	public UUID getPlayerRef() {
		return this.playerRef;
	}

	public UserStatus getStatus() {
		return this.status;
	}

	public LocalDate getBirthDate() {
		return this.birthDate;
	}

	public Instant getAgeVerifiedAt() {
		return this.ageVerifiedAt;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}
}

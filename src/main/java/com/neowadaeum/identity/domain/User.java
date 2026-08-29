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
 * <p><b>탈퇴해도 이 행은 남는다</b> (R12.4, B-61). 동의 이력이 이 행을 앵커로 삼고 그것은 법정
 * 기간 동안 보관해야 한다 — 파기가 지우는 것은 회원이 아니라 <b>회원과 기록을 잇는 고리</b>다
 * (R12.5).
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
	 *
	 * <p><b>탈퇴 파기만 이 값을 비운다</b> (R12.5, B-61). 그것은 값을 바꾸는 일이 아니라
	 * <b>고리를 끊는 일</b>이며, 그래서 엔티티가 아니라 벌크 UPDATE 로만 일어난다 —
	 * {@code updatable = false} 는 그대로 두어 <b>평상시 경로에는 바꿀 방법이 없게</b> 한다.
	 */
	@Column(name = "player_ref", updatable = false)
	private UUID playerRef;

	@Column(name = "status", nullable = false)
	private UserStatus status;

	/**
	 * 역할 (R14.6, S-4).
	 *
	 * <p><b>가입으로는 {@link UserRole#USER} 만 얻는다.</b> 승격 경로를 코드에 두지 않는다 —
	 * 두는 순간 그것이 공개 레포에 적힌 승격 절차가 된다 (S-11).
	 */
	@Column(name = "role", nullable = false)
	private UserRole role;

	/** 가입 연령 게이트(B-13)가 쓰는 원본. 미확인 회원은 {@code null} 이다. */
	@Column(name = "birth_date")
	private LocalDate birthDate;

	/** 연령 확인을 마친 시각 (R10.2). 확인 전에는 {@code null} 이다. */
	@Column(name = "age_verified_at")
	private Instant ageVerifiedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	/**
	 * 파기한 시각 (R12.5, B-61). 파기 전에는 {@code null} 이다.
	 *
	 * <p><b>"지웠다"의 근거는 값이 비었다는 사실이 아니라 언제 지웠는가다.</b>
	 */
	@Column(name = "purged_at")
	private Instant purgedAt;

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
		user.role = UserRole.USER;
		user.birthDate = birthDate;
		user.createdAt = now;
		return user;
	}

	/** 연령 확인 완료를 기록한다 (B-13, R10.2). 판정 자체는 {@code AgeGate} 의 몫이다. */
	public void markAgeVerified(Instant verifiedAt) {
		this.ageVerifiedAt = verifiedAt;
	}

	/**
	 * 탈퇴 신청 (R12.5, B-62).
	 *
	 * <p><b>지우는 것이 아니라 상태다.</b> 실제 파기는 배치가 하고 (B-61), 그 사이에도 이 회원은
	 * 로그인하지 못한다 — 상태가 곧 차단이다.
	 *
	 * <p><b>정지된 회원도 탈퇴할 수 있다.</b> 탈퇴는 서비스가 주는 혜택이 아니라 회원의 권리이며,
	 * 정지를 이유로 막으면 <b>나갈 수 없는 계정</b>이 생긴다.
	 *
	 * <p><b>되돌릴 수 없다.</b> 되돌리는 경로를 두면 파기 배치가 지운 뒤에 돌아오는 회원이
	 * 생기고, 그 회원에게는 기록도 동의도 없다.
	 *
	 * @return 이번 호출이 상태를 바꿨으면 {@code true}. 이미 탈퇴한 회원이면 {@code false}
	 */
	public boolean withdraw() {
		if (this.status == UserStatus.WITHDRAWN) {
			return false;
		}
		this.status = UserStatus.WITHDRAWN;
		return true;
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

	/** R14.6 — 역할은 <b>세 조건 중 하나</b>다. 이것만으로 관리자가 되지 않는다. */
	public UserRole getRole() {
		return this.role;
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

	public Instant getPurgedAt() {
		return this.purgedAt;
	}
}

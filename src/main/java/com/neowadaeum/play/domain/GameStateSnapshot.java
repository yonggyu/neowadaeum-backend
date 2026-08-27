package com.neowadaeum.play.domain;

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
 * 턴마다 1행 저장되는 GameState (§3.2, I-5).
 *
 * <p><b>I-5 — append-only 다. 덮어쓰지 않는다.</b> 덮어쓰는 순간 관리자 롤백(R14.4)이 "스냅샷과 요약을
 * 함께 되돌린다"를 지킬 수 없게 되고, 되돌릴 대상 자체가 사라진다. 그래서 이 클래스에는 상태를 바꾸는
 * 수단이 없다 — 되돌리기는 새 행을 쓰거나 {@code deletedAt} 을 채우는 방식이며 그 구현은 B-42 다.
 *
 * <p>DB 쪽 유일성도 <b>살아 있는 행 기준</b>이다({@code deleted_at IS NULL} partial unique index).
 * 전체 기준으로 잠그면 재생성이 같은 턴 번호로 새 행을 남길 수 없어 결국 UPDATE 로 되돌아간다.
 *
 * <p><b>{@code chapter} · {@code turn} 은 AI 가 바꿀 수 없다 (I-9).</b> 상태 병합은 화이트리스트 필터 →
 * clamp → 병합 순서이며(S-5 / B-26), 이 클래스는 그 결과를 받아 적기만 한다.
 */
@Entity
@Table(name = "game_state_snapshot")
public class GameStateSnapshot {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "session_id", nullable = false, updatable = false)
	private UUID sessionId;

	@Column(name = "turn_no", nullable = false, updatable = false)
	private int turnNo;

	/** 챕터·턴·장소·시간·호감도·플래그·인벤토리의 구조화 JSON (§3.2). */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "state", nullable = false, updatable = false)
	private String state;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	/** 롤백으로 되돌려진 시각 (§13-9). 지우지 않고 표시만 한다. */
	@Column(name = "deleted_at")
	private Instant deletedAt;

	protected GameStateSnapshot() {
	}

	public static GameStateSnapshot capture(UUID sessionId, int turnNo, String state, Instant now) {
		GameStateSnapshot snapshot = new GameStateSnapshot();
		snapshot.sessionId = sessionId;
		snapshot.turnNo = turnNo;
		snapshot.state = state;
		snapshot.createdAt = now;
		return snapshot;
	}

	/**
	 * 되돌리기로 접힌다 (R14.4, B-42).
	 *
	 * <p><b>이 클래스의 주석이 예고한 그 경로다</b> — 스냅샷은 UPDATE 하지 않으므로(I-5),
	 * 되돌리기는 값을 고치는 것이 아니라 <b>행을 접는 것</b>이다. 살아 있는 행 기준의 유일
	 * 인덱스가 그 위에서 성립한다.
	 */
	public void softDelete(Instant now) {
		if (this.deletedAt == null) {
			this.deletedAt = now;
		}
	}

	public UUID getId() {
		return this.id;
	}

	public UUID getSessionId() {
		return this.sessionId;
	}

	public int getTurnNo() {
		return this.turnNo;
	}

	public String getState() {
		return this.state;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public Instant getDeletedAt() {
		return this.deletedAt;
	}
}

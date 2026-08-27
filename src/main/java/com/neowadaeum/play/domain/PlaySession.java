package com.neowadaeum.play.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 한 사용자가 한 작품을 진행하는 단위 (§4.2).
 *
 * <p><b>I-4 — 세션은 생성 시 {@code storyVersionId} · {@code providerId} · {@code modelId} 에 고정된다.</b>
 * 진행 중인 세션은 작품의 새 버전에도, provider 전환에도 영향받지 않는다. 그래서 이 값들에는 변경
 * 수단을 두지 않는다. 바꿀 방법이 없는 것이 "바꾸지 않기로 했다"보다 강하다.
 *
 * <p><b>I-3 / §5.3 — 회원 식별정보를 담지 않는다.</b> 이 스토어가 아는 사람 표기는 {@code playerRef}
 * (UUID) 하나뿐이며 {@code user.id} · 이메일 · 생년월일은 identity 스키마에만 있다.
 *
 * <p><b>I-6 — {@code turnNo} 는 낙관적 잠금 키다.</b> 클라이언트가 보낸 값과 다르면 409 다(§4.3-2).
 * 상태 전이(턴 진행 · 종료 · 포기)는 S-9 / B-17 의 범위이고 여기에는 아직 없다.
 */
@Entity
@Table(name = "play_session")
public class PlaySession {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	/** 회원당 1개의 UUID. 회원정보와 무관하다 (§3.3, I-3). */
	@Column(name = "player_ref", nullable = false, updatable = false)
	private UUID playerRef;

	/** catalog 스키마의 작품. <b>FK 를 걸지 않는다</b> — 스키마 간 FK 금지 (§5.3). */
	@Column(name = "story_id", nullable = false, updatable = false)
	private UUID storyId;

	/** 생성 시점의 {@code story.current_version_id} 를 고정한 값 (I-4). */
	@Column(name = "story_version_id", nullable = false, updatable = false)
	private UUID storyVersionId;

	@Column(name = "provider_id", nullable = false, updatable = false)
	private String providerId;

	@Column(name = "model_id", nullable = false, updatable = false)
	private String modelId;

	@Column(name = "status", nullable = false)
	private SessionStatus status;

	@Column(name = "turn_no", nullable = false)
	private int turnNo;

	@Column(name = "chapter_no", nullable = false)
	private int chapterNo;

	/** I-18 — 자유입력은 이 세션에서만 허용된다. 사용자 소유 세션에는 통로를 만들지 않는다. */
	@Column(name = "is_test_session", nullable = false, updatable = false)
	private boolean testSession;

	/** 도달한 엔딩. catalog 스키마이므로 FK 없이 UUID 만 갖는다 (§4.6). */
	@Column(name = "current_ending_id")
	private UUID currentEndingId;

	/** Resume 화면이 "어디까지 왔는지"를 보여줄 때 쓴다 (§2.5, §4.7). */
	@Column(name = "last_scene_summary")
	private String lastSceneSummary;

	@Column(name = "last_choice_text")
	private String lastChoiceText;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	/** 90일 무활동 만료 시각 (§4.7). {@code status = 'expired'} 의 판정 근거다. */
	@Column(name = "expires_at")
	private Instant expiresAt;

	/** 사용자 삭제. §4.7 의 {@code deleted} resume 상태 판정 근거다. */
	@Column(name = "deleted_at")
	private Instant deletedAt;

	protected PlaySession() {
	}

	/**
	 * 세션을 시작한다. 턴 0 · 챕터 1 에서 출발하며, 턴 1 은 §4.3 파이프라인이 만든다 (§4.2).
	 *
	 * <p>{@code now} 를 인자로 받는 이유는 테스트가 시간을 고정할 수 있어야 하기 때문이다.
	 * 도메인 안에서 현재 시각을 읽으면 같은 입력이 같은 결과를 내지 않는다.
	 */
	public static PlaySession start(UUID playerRef, UUID storyId, UUID storyVersionId, String providerId,
			String modelId, boolean testSession, Instant now) {
		PlaySession session = new PlaySession();
		session.playerRef = playerRef;
		session.storyId = storyId;
		session.storyVersionId = storyVersionId;
		session.providerId = providerId;
		session.modelId = modelId;
		session.testSession = testSession;
		session.status = SessionStatus.ACTIVE;
		session.turnNo = 0;
		session.chapterNo = 1;
		session.createdAt = now;
		session.updatedAt = now;
		return session;
	}

	/**
	 * 턴 하나가 저장됐다 (§4.3-11).
	 *
	 * <p><b>I-6 — {@code turn_no} 는 여기서만 증가한다.</b> 낙관적 잠금의 키이므로 다른 경로에서
	 * 흔들리면 동시 요청 판정이 무너진다. 실패한 턴은 이 메서드에 오지 않으므로 세션 상태가
	 * 그대로 남는다 (R6.6).
	 *
	 * <p><b>I-4 는 그대로다</b> — {@code story_version_id} · {@code provider_id} · {@code model_id} 를
	 * 바꾸는 수단은 여기에도 없다.
	 */
	public void recordTurn(int newTurnNo, int newChapterNo, Instant now) {
		if (newTurnNo != this.turnNo + 1) {
			throw new IllegalArgumentException(
					"turn numbers advance by one (I-6): expected %d, got %d".formatted(this.turnNo + 1, newTurnNo));
		}
		this.turnNo = newTurnNo;
		this.chapterNo = newChapterNo;
		this.updatedAt = now;
	}

	/**
	 * 엔딩에 도달해 종료한다 (R7.8).
	 *
	 * <p>{@code completed_at} 을 함께 채운다 — 마이그레이션의 CHECK 가 {@code status = 'completed'} 와
	 * 짝을 강제하므로, 한쪽만 세우면 저장에서 거절된다.
	 */
	/**
	 * 다시 시작으로 버려진다 (§13-9).
	 *
	 * <p><b>지우지 않는다.</b> 지나간 플레이는 기록이며, 그 위에 요약·스냅샷·턴이 매달려 있다.
	 * 상태만 바꾸면 "작품당 active 1개" 인덱스가 새 세션에 자리를 내준다.
	 */
	public void abandon(Instant now) {
		this.status = SessionStatus.ABANDONED;
		this.updatedAt = now;
	}

	/**
	 * 사용자가 지운다 (§13.4, §4.7).
	 *
	 * <p><b>soft delete 다</b> — {@code deleted_at} 을 채울 뿐 행을 지우지 않는다. 롤백(R14.4)과
	 * 같은 이유이며, 실제 파기는 보관 주기를 지키는 배치의 몫이다 (R12.4, B-61).
	 *
	 * <p><b>진행 중이었을 때만 상태를 바꾼다.</b> 남겨 두면 "작품당 active 1개" 인덱스가 지운
	 * 세션 때문에 새 세션을 막는다 — 사용자가 보기에는 지웠는데 다시 시작할 수 없는 상태다.
	 *
	 * <p><b>끝난 세션의 상태는 건드리지 않는다.</b> 그 인덱스는 {@code active} 만 보므로 바꿀
	 * 이유가 없고, 바꾸면 <b>완주했다는 사실이 사라진다.</b> 마이그레이션의 CHECK 도 그것을
	 * 거부한다 — {@code completed} 와 {@code completed_at} 은 함께 가거나 함께 없어야 한다 (V2).
	 */
	public void deleteBy(Instant now) {
		if (this.status == SessionStatus.ACTIVE) {
			this.status = SessionStatus.ABANDONED;
		}
		this.deletedAt = now;
		this.updatedAt = now;
	}

	/**
	 * 관리자가 되돌린다 (R14.4, B-42).
	 *
	 * <p><b>{@code turn_no} 가 줄어드는 유일한 자리다.</b> {@link #recordTurn} 이 "하나씩만
	 * 오른다"를 지키는 것과 짝이며, 되돌리기는 그 규칙의 예외가 아니라 <b>별도의 사건</b>이다 —
	 * 그래서 메서드를 나눈다. 한 메서드가 양방향을 다루면 오타 하나가 조용한 되감기가 된다.
	 *
	 * <p><b>끝났다는 사실을 함께 지운다.</b> 엔딩 뒤로 되돌리면서 {@code completed} 를 남기면
	 * 마이그레이션의 CHECK 가 거절하고, 통과하더라도 <b>끝난 세션이 계속 진행되는</b> 상태가
	 * 된다. {@code completed_at} 과 {@code current_ending_id} 도 같이 비운다.
	 *
	 * <p><b>지워진 세션은 되돌리지 않는다.</b> 되살릴 대상이 아니라 파기를 기다리는 것이다.
	 */
	public void rewindTo(int targetTurnNo, int targetChapterNo, Instant now) {
		if (this.deletedAt != null) {
			throw new IllegalStateException("지워진 세션은 되돌리지 않는다: " + this.id);
		}
		if (targetTurnNo < 0 || targetTurnNo >= this.turnNo) {
			throw new IllegalArgumentException(
					"되돌릴 지점은 현재보다 앞이어야 한다 (I-6): 현재 %d, 요청 %d"
							.formatted(this.turnNo, targetTurnNo));
		}
		this.turnNo = targetTurnNo;
		this.chapterNo = targetChapterNo;
		this.status = SessionStatus.ACTIVE;
		this.currentEndingId = null;
		this.completedAt = null;
		this.updatedAt = now;
	}

	public void complete(UUID endingId, Instant now) {
		this.status = SessionStatus.COMPLETED;
		this.currentEndingId = endingId;
		this.completedAt = now;
		this.updatedAt = now;
	}

	public UUID getId() {
		return this.id;
	}

	public UUID getPlayerRef() {
		return this.playerRef;
	}

	public UUID getStoryId() {
		return this.storyId;
	}

	public UUID getStoryVersionId() {
		return this.storyVersionId;
	}

	public String getProviderId() {
		return this.providerId;
	}

	public String getModelId() {
		return this.modelId;
	}

	public SessionStatus getStatus() {
		return this.status;
	}

	public int getTurnNo() {
		return this.turnNo;
	}

	public int getChapterNo() {
		return this.chapterNo;
	}

	public boolean isTestSession() {
		return this.testSession;
	}

	public UUID getCurrentEndingId() {
		return this.currentEndingId;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public Instant getUpdatedAt() {
		return this.updatedAt;
	}

	public String getLastSceneSummary() {
		return this.lastSceneSummary;
	}

	public String getLastChoiceText() {
		return this.lastChoiceText;
	}

	public Instant getExpiresAt() {
		return this.expiresAt;
	}

	public Instant getCompletedAt() {
		return this.completedAt;
	}

	public Instant getDeletedAt() {
		return this.deletedAt;
	}
}

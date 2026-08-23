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

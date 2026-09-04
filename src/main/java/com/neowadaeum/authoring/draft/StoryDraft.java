package com.neowadaeum.authoring.draft;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

/**
 * 작성 중인 원고 (§2.4, §8.1).
 *
 * <p><b>{@code payload} 는 검수 대상 원문이다</b> (R2.4). 단계별 입력을 그대로 담으며, 서버는
 * 그 안을 해석하지 않는다 — 해석은 검수(B-50, B-54)의 몫이고, 여기서 미리 풀어 두면
 * <b>단계가 늘 때마다 컬럼이 는다.</b>
 *
 * <p><b>{@code author_ref} 는 {@code player_ref} 다.</b> 비-Identity 스토어는 {@code user.id} 를
 * 저장하지 않는다.
 */
@Entity
@Table(name = "story_draft")
public class StoryDraft {

	@Id
	@GeneratedValue
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "author_ref", nullable = false, updatable = false)
	private UUID authorRef;

	/** 재편집이면 기존 작품을 가리킨다. 새 원고는 비어 있다. */
	@Column(name = "story_id")
	private UUID storyId;

	/**
	 * 마지막 미리보기가 발행한 임시 작품 (#332).
	 *
	 * <p>{@link #storyId} 와 <b>다른 자리다</b> — 그쪽은 제출된 작품이고 이쪽은 파기를 기다리는
	 * 사본이다 (§13-5).
	 */
	@Column(name = "preview_story_id")
	private UUID previewStoryId;

	/** 그 작품 위에서 돈 테스트 세션 (#332). 검수 상세가 이 세션의 턴을 읽는다. */
	@Column(name = "preview_session_id")
	private UUID previewSessionId;

	/** 마지막 미리보기 시각 (#332). 검수자는 <b>얼마나 오래된 미리보기인지</b> 알아야 한다. */
	@Column(name = "previewed_at")
	private Instant previewedAt;

	@Column(name = "step", nullable = false)
	private int step;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "payload", nullable = false)
	private String payload;

	@Column(name = "safety_state", nullable = false)
	private String safetyState;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "safety_findings", nullable = false)
	private String safetyFindings;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected StoryDraft() {
	}

	/** 새 원고. 1단계에서 시작하고 아직 검수를 거치지 않았다. */
	public static StoryDraft start(UUID authorRef, Instant now) {
		StoryDraft draft = new StoryDraft();
		draft.authorRef = authorRef;
		draft.step = 1;
		draft.payload = "{}";
		draft.safetyState = DraftSafetyState.CLEAN.columnValue();
		draft.safetyFindings = "[]";
		draft.createdAt = now;
		draft.updatedAt = now;
		return draft;
	}

	/**
	 * 단계별 입력을 저장한다.
	 *
	 * <p><b>단계가 뒤로 갈 수 있다.</b> 작성자는 앞 단계를 고치러 돌아간다 — 앞으로만 가게
	 * 하면 오타 하나를 고치려고 원고를 새로 써야 한다.
	 */
	public void save(int step, String payload, Instant now) {
		this.step = step;
		this.payload = payload;
		this.updatedAt = now;
	}

	/** 검수 결과를 기록한다 (B-50). {@code blocked} 면 다음 단계가 막힌다 (R8.3). */
	public void recordPrecheck(DraftSafetyState state, String findings, Instant now) {
		this.safetyState = state.columnValue();
		this.safetyFindings = findings;
		this.updatedAt = now;
	}

	/**
	 * 마지막 미리보기가 만든 것을 가리키게 한다 (#332, §13-5).
	 *
	 * <p><b>{@code storyId} 와 다른 자리다.</b> 그 컬럼은 <b>제출된 작품</b> 하나를 가리키며
	 * 재제출이 같은 작품에 버전을 얹는다 (R8.8) — 미리보기 작품을 거기 넣으면 제출이 그것을
	 * 덮어쓰거나 재제출이 미리보기 작품에 버전을 얹는다.
	 *
	 * <p><b>마지막 것만 남는다.</b> 여러 번 돌리면 이전 미리보기는 연결이 끊겨 보관 기간 뒤에
	 * 파기된다 (§13-37) — 검수자가 보는 것은 <b>작성자가 마지막으로 확인한 것</b>이다.
	 */
	public void linkPreview(UUID previewStoryId, UUID previewSessionId, Instant now) {
		this.previewStoryId = previewStoryId;
		this.previewSessionId = previewSessionId;
		this.previewedAt = now;
		this.updatedAt = now;
	}

	/**
	 * 이 원고가 만든 작품을 가리키게 한다 (B-54).
	 *
	 * <p><b>재제출은 같은 작품에 새 버전을 얹는다</b> (R8.8) — 원고마다 작품이 새로 생기면
	 * 고칠 때마다 카탈로그에 <b>같은 작품이 여럿</b>이 된다.
	 */
	public void linkStory(UUID storyId, Instant now) {
		this.storyId = storyId;
		this.updatedAt = now;
	}

	public UUID getPreviewStoryId() {
		return this.previewStoryId;
	}

	public UUID getPreviewSessionId() {
		return this.previewSessionId;
	}

	public Instant getPreviewedAt() {
		return this.previewedAt;
	}

	public boolean isOwnedBy(UUID playerRef) {
		return this.authorRef.equals(playerRef);
	}

	public UUID getId() {
		return this.id;
	}

	public UUID getAuthorRef() {
		return this.authorRef;
	}

	public UUID getStoryId() {
		return this.storyId;
	}

	public int getStep() {
		return this.step;
	}

	public String getPayload() {
		return this.payload;
	}

	public DraftSafetyState getSafetyState() {
		return DraftSafetyState.valueOf(this.safetyState.toUpperCase(java.util.Locale.ROOT));
	}

	public String getSafetyFindings() {
		return this.safetyFindings;
	}

	public Instant getUpdatedAt() {
		return this.updatedAt;
	}
}

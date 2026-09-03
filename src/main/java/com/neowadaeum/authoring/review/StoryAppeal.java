package com.neowadaeum.authoring.review;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 재검토 요청 한 건 (#290, §13-59, R8.9).
 *
 * <p><b>판정이 아니라 요청이다.</b> 그래서 {@link StoryReview} 에 넣지 않았다 — 검수 이력의
 * 마지막 행은 <b>큐가 기다린 시각으로 읽는 값</b>이고 ({@link ReviewQueueService#pending()}),
 * 거기에 작성자의 요청이 섞이면 이의를 제기할 때마다 줄 맨 뒤로 밀린다.
 *
 * <p><b>append-only 로 다룬다.</b> 정지가 두 번 일어나면 요청도 두 번 남고, 그 둘은 서로 다른
 * 사실이다. 열려 있는 요청인지는 <b>인간 판정과 견주어 파생된다</b> —
 * {@link StoryAppealRepository#storyIdsWithOpenAppeal} 이 그 규칙이다.
 *
 * <p><b>{@code reason} 은 검수자만 읽는다</b> (S-11).
 */
@Entity
@Table(name = "story_appeal")
public class StoryAppeal {

	@Id
	@GeneratedValue
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "story_id", nullable = false, updatable = false)
	private UUID storyId;

	/** 작성자의 {@code player_ref}. <b>{@code user.id} 가 아니다</b> (§5.3). */
	@Column(name = "author_ref", nullable = false, updatable = false)
	private UUID authorRef;

	@Column(name = "reason", nullable = false, updatable = false)
	private String reason;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected StoryAppeal() {
	}

	public static StoryAppeal of(UUID storyId, UUID authorRef, String reason, Instant now) {
		StoryAppeal appeal = new StoryAppeal();
		appeal.storyId = storyId;
		appeal.authorRef = authorRef;
		appeal.reason = reason;
		appeal.createdAt = now;
		return appeal;
	}

	public UUID getId() {
		return this.id;
	}

	public UUID getStoryId() {
		return this.storyId;
	}

	public UUID getAuthorRef() {
		return this.authorRef;
	}

	public String getReason() {
		return this.reason;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}
}

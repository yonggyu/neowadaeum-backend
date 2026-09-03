package com.neowadaeum.authoring.review;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 검수 한 건 (§2.4, R8.7).
 *
 * <p><b>append-only 로 다룬다.</b> 같은 작품에 검수가 여러 번 일어난다 — 재제출, {@code unlisted}
 * → {@code public} 재검수, 블록리스트 갱신 후 재스캔(B-59). 마지막 판정만 남기면 <b>왜 그렇게
 * 됐는지</b>를 잃는다.
 *
 * <p><b>{@code reasons} 는 카테고리만 담는다</b> (R8.7). 어떤 항목에 걸렸는지를 담으면 이 표가
 * 우회 사전이 된다 (S-11).
 */
@Entity
@Table(name = "story_review")
public class StoryReview {

	@Id
	@GeneratedValue
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "story_id", nullable = false, updatable = false)
	private UUID storyId;

	@Column(name = "stage", nullable = false, updatable = false)
	private String stage;

	@Column(name = "verdict", nullable = false, updatable = false)
	private String verdict;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "reasons", nullable = false, updatable = false)
	private String reasons;

	/** 자동 검수에는 사람이 없다. DB 의 CHECK 가 인간 검수에만 이 값을 요구한다. */
	@Column(name = "reviewer_ref", updatable = false)
	private UUID reviewerRef;

	@Column(name = "note", updatable = false)
	private String note;

	@Column(name = "reviewed_at", nullable = false, updatable = false)
	private Instant reviewedAt;

	protected StoryReview() {
	}

	public static StoryReview of(UUID storyId, ReviewStage stage, ReviewVerdict verdict,
			String reasonsJson, UUID reviewerRef, String note, Instant now) {
		StoryReview review = new StoryReview();
		review.storyId = storyId;
		review.stage = stage.columnValue();
		review.verdict = verdict.columnValue();
		review.reasons = reasonsJson;
		review.reviewerRef = reviewerRef;
		review.note = note;
		// timestamptz 는 마이크로초까지만 담는다. 자르지 않으면 방금 만든 이 객체가 들고 있는
		// 값과 다시 읽은 값이 나노초 자리에서 갈라진다 — 판정 응답이 돌려준 시각과 다음 조회가
		// 돌려주는 시각이 다른 것으로 보인다 (§13-57 이 이 값을 응답에 싣기 시작했다).
		review.reviewedAt = now.truncatedTo(ChronoUnit.MICROS);
		return review;
	}

	public UUID getId() {
		return this.id;
	}

	public UUID getStoryId() {
		return this.storyId;
	}

	public ReviewStage getStage() {
		return ReviewStage.valueOf(this.stage.toUpperCase(java.util.Locale.ROOT));
	}

	public ReviewVerdict getVerdict() {
		return ReviewVerdict.valueOf(this.verdict.toUpperCase(java.util.Locale.ROOT));
	}

	public String getReasons() {
		return this.reasons;
	}

	public UUID getReviewerRef() {
		return this.reviewerRef;
	}

	public Instant getReviewedAt() {
		return this.reviewedAt;
	}
}

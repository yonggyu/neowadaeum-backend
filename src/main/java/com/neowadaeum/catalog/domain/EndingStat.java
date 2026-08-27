package com.neowadaeum.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 엔딩 도달률의 원자료 (§2.6).
 *
 * <p><b>집계 키가 {@code (storyId, endingNo)} 다.</b> {@code endingId} 로 세면 버전을 발행할
 * 때마다 행이 새로 생겨 도달률이 0 부터 다시 시작한다 — 같은 엔딩인데 통계가 끊긴다
 * (§13-1 부수 영향).
 *
 * <p><b>I-20 — 배치가 갱신한다</b> (B-39). 조회 경로가 이 값을 계산하지 않는다.
 *
 * <p><b>R2.8 의 "표본 50 미만이면 {@code null}" 은 여기서 판정하지 않는다.</b> 이 표는 센 값만
 * 갖고, 노출 여부는 조회 쪽이 정한다 — 임계값이 바뀌어도 저장된 원자료는 그대로여야 한다.
 */
@Entity
@Table(name = "ending_stat")
@IdClass(EndingStat.Key.class)
public class EndingStat {

	@Id
	@Column(name = "story_id", nullable = false, updatable = false)
	private UUID storyId;

	@Id
	@Column(name = "ending_no", nullable = false, updatable = false)
	private int endingNo;

	/** 이 엔딩에 도달한 세션 수. */
	@Column(name = "reached_count", nullable = false)
	private long reachedCount;

	/** 분모. 그 작품에서 엔딩에 도달한 세션 전체 수다. */
	@Column(name = "total_completed_count", nullable = false)
	private long totalCompletedCount;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected EndingStat() {
	}

	/** 배치가 계산한 값으로 한 행을 만든다 (B-39). */
	public static EndingStat of(UUID storyId, int endingNo, long reachedCount, long totalCompletedCount,
			Instant now) {
		if (storyId == null) {
			throw new IllegalArgumentException("storyId is required");
		}
		if (reachedCount < 0 || totalCompletedCount < reachedCount) {
			// DB CHECK 와 같은 규칙이다. 여기서 먼저 걸리면 원인이 배치라는 것이 드러난다.
			throw new IllegalArgumentException("counts must satisfy 0 <= reached <= totalCompleted");
		}
		EndingStat stat = new EndingStat();
		stat.storyId = storyId;
		stat.endingNo = endingNo;
		stat.reachedCount = reachedCount;
		stat.totalCompletedCount = totalCompletedCount;
		stat.updatedAt = now;
		return stat;
	}

	public UUID getStoryId() {
		return this.storyId;
	}

	public int getEndingNo() {
		return this.endingNo;
	}

	public long getReachedCount() {
		return this.reachedCount;
	}

	public long getTotalCompletedCount() {
		return this.totalCompletedCount;
	}

	public Instant getUpdatedAt() {
		return this.updatedAt;
	}

	/** 복합 PK. JPA 는 {@code @IdClass} 에 인자 없는 생성자와 값 동등성을 요구한다. */
	public static class Key implements Serializable {

		private UUID storyId;

		private int endingNo;

		public Key() {
		}

		public Key(UUID storyId, int endingNo) {
			this.storyId = storyId;
			this.endingNo = endingNo;
		}

		@Override
		public boolean equals(Object other) {
			return (other instanceof Key key) && Objects.equals(this.storyId, key.storyId)
					&& this.endingNo == key.endingNo;
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.storyId, this.endingNo);
		}
	}
}

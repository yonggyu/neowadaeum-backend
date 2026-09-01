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
 * 오래된 턴의 압축 (§4.2, R4.5, B-34).
 *
 * <p><b>R2.6 — append-only 다.</b> {@link GameStateSnapshot} 과 같은 이유다 (I-5). 요약을 제자리에서
 * 고치면 관리자 롤백(R14.4)이 "스냅샷과 요약을 <b>함께</b> 되돌린다"를 지킬 수 없다 — 되돌릴 이전
 * 값이 남아 있지 않기 때문이다. 그래서 이 클래스에는 본문을 바꾸는 수단이 없다.
 *
 * <p><b>재압축도 새 행이다</b> (R4.5). 요약이 예산(§4.3 의 SUMMARY 600토큰)을 넘으면 더 짧게 다시
 * 압축하는데, 그것은 <b>같은 {@code uptoTurnNo} 에 대한 다른 요약</b>이다. 그래서 DB 에도
 * {@code (session_id, upto_turn_no)} 유일성이 없다 — 잠그면 재압축이 UPDATE 로 돌아간다.
 *
 * <p><b>원문을 대신하지 않는다</b> (R4.8). 턴 원문은 DB 에 그대로 남는다. 압축은 <b>프롬프트에
 * 무엇을 싣는가</b>의 문제이지 보관 정책이 아니다 — History 화면(B-35)은 전체 턴을 읽는다.
 */
@Entity
@Table(name = "story_summary")
public class StorySummary {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "session_id", nullable = false, updatable = false)
	private UUID sessionId;

	/** 이 요약이 포함하는 마지막 턴 번호. 그 뒤의 턴은 원문으로 프롬프트에 실린다 (R4.7). */
	@Column(name = "upto_turn_no", nullable = false, updatable = false)
	private int uptoTurnNo;

	@Column(name = "summary_text", nullable = false, updatable = false)
	private String summaryText;

	/**
	 * 토큰 추정치.
	 *
	 * <p><b>추정이라고 적어 둔다.</b> §13-18 이 정한 계수 기반 계산이며 벤더 토크나이저가 아니다.
	 * 재압축 여부(R4.5)를 이 값으로 판단하므로, 계수가 조정되면 그 판단도 함께 움직인다.
	 */
	@Column(name = "token_estimate", nullable = false, updatable = false)
	private int tokenEstimate;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	/** 롤백으로 되돌려진 시각 (§13-9, R14.4). 지우지 않고 표시만 한다. */
	@Column(name = "deleted_at")
	private Instant deletedAt;

	protected StorySummary() {
	}

	public static StorySummary of(UUID sessionId, int uptoTurnNo, String summaryText, int tokenEstimate,
			Instant now) {
		if (sessionId == null) {
			throw new IllegalArgumentException("sessionId is required");
		}
		if (uptoTurnNo < 1) {
			// 0 턴까지의 요약은 "요약할 것이 없다"는 뜻이며 행을 만들 이유가 없다.
			throw new IllegalArgumentException("uptoTurnNo must be positive");
		}
		if (summaryText == null || summaryText.isBlank()) {
			throw new IllegalArgumentException("summaryText is required");
		}
		if (tokenEstimate < 0) {
			throw new IllegalArgumentException("tokenEstimate must not be negative");
		}

		StorySummary summary = new StorySummary();
		summary.sessionId = sessionId;
		summary.uptoTurnNo = uptoTurnNo;
		summary.summaryText = summaryText;
		summary.tokenEstimate = tokenEstimate;
		summary.createdAt = now;
		return summary;
	}

	/**
	 * 되돌리기로 접힌다 (R14.4, B-42).
	 *
	 * <p><b>스냅샷과 함께 접힌다.</b> 요약만 남으면 상태와 이야기가 어긋난 채로 다음 턴이
	 * 만들어진다 — R14.4 가 "함께"라고 적은 것이 이것이다.
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

	public int getUptoTurnNo() {
		return this.uptoTurnNo;
	}

	public String getSummaryText() {
		return this.summaryText;
	}

	public int getTokenEstimate() {
		return this.tokenEstimate;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public Instant getDeletedAt() {
		return this.deletedAt;
	}
}

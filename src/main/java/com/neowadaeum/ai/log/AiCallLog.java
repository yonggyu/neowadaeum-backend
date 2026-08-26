package com.neowadaeum.ai.log;

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
 * AI 호출 한 건의 원문과 계측 (B-11, §13-4).
 *
 * <p><b>원문 보관처는 여기뿐이다</b> (S-3). 애플리케이션 로그에는 프롬프트도 응답도 남기지
 * 않는다 — 그 규칙 때문에 B-22 로 실 Provider 가 붙은 뒤에도 <b>원문을 사후에 볼 방법이
 * 없었다</b> (§13-20). 이 테이블이 그 공백을 닫는다.
 *
 * <p><b>I-3 — {@code player_ref} 를 담지 않는다.</b> 컬럼이 존재하지 않으며, 역추적은
 * {@code session_id} 로만 한다. 회원까지 이어야 한다면 {@code play} 스토어를 한 번 더 거쳐야
 * 하고, <b>그 한 겹이 이 테이블만으로는 사람을 특정할 수 없게 만든다.</b>
 *
 * <p><b>스키마 간 FK 가 없다</b> (§5.3). {@code sessionId} 는 {@code play}, {@code draftId} 는
 * {@code authoring} 의 값이며 참조는 애플리케이션 레벨에서만 한다.
 *
 * <p><b>append-only 다.</b> 세터가 없고 갱신 경로도 없다 — 호출 기록을 나중에 고치는 것은
 * 감사 대상을 고치는 것이다 (R2.10, R12.3).
 */
@Entity
@Table(name = "ai_call_log")
public class AiCallLog {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	/** 어느 세션의 호출인가. 작성 미리보기(B-53)처럼 세션이 없는 호출은 {@code null} 이다. */
	@Column(name = "session_id", updatable = false)
	private UUID sessionId;

	/** UGC 초안 경로의 호출 (B-50, B-52). 턴 생성이면 {@code null} 이다. */
	@Column(name = "draft_id", updatable = false)
	private UUID draftId;

	/** {@code turn} · {@code summary} · {@code safety} · {@code outline}. R3.6 의 용도와 같은 축이다. */
	@Column(name = "purpose", nullable = false, updatable = false)
	private String purpose;

	@Column(name = "provider_id", nullable = false, updatable = false)
	private String providerId;

	@Column(name = "model_id", nullable = false, updatable = false)
	private String modelId;

	/** R3.7 — fallback 이 발동했다면 원래 지목됐던 provider. 아니면 {@code null}. */
	@Column(name = "fallback_from", updatable = false)
	private String fallbackFrom;

	@Column(name = "request_raw", nullable = false, updatable = false)
	private String requestRaw;

	/** 호출이 실패했으면 응답이 없다. 그 경우에도 요청은 남는다 — 무엇을 보냈는지가 단서다. */
	@Column(name = "response_raw", updatable = false)
	private String responseRaw;

	@Column(name = "input_tokens", updatable = false)
	private Integer inputTokens;

	@Column(name = "output_tokens", updatable = false)
	private Integer outputTokens;

	@Column(name = "latency_ms", updatable = false)
	private Integer latencyMs;

	/** 백만분의 1 단위. 부동소수로 돈을 세지 않는다. */
	@Column(name = "cost_micro", updatable = false)
	private Long costMicro;

	/** R9.3 — 세이프티 카테고리 배열. 통과면 빈 배열이다. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "safety_flags", nullable = false, updatable = false)
	private String safetyFlags;

	/** R5.8 · R3.3 — 재요청은 같은 턴의 별도 호출이다. 1부터 센다. */
	@Column(name = "attempt_no", nullable = false, updatable = false)
	private int attemptNo;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected AiCallLog() {
	}

	/**
	 * 기록 한 건을 만든다.
	 *
	 * <p><b>정적 팩토리 하나뿐이다.</b> 세터를 두면 저장 뒤에 고칠 수 있게 되고, 그 순간 이
	 * 테이블은 감사 기록이기를 그만둔다.
	 */
	public static AiCallLog record(Draft draft, Instant now) {
		AiCallLog log = new AiCallLog();
		log.sessionId = draft.sessionId();
		log.draftId = draft.draftId();
		log.purpose = draft.purpose();
		log.providerId = draft.providerId();
		log.modelId = draft.modelId();
		log.fallbackFrom = draft.fallbackFrom();
		log.requestRaw = draft.requestRaw();
		log.responseRaw = draft.responseRaw();
		log.inputTokens = draft.inputTokens();
		log.outputTokens = draft.outputTokens();
		log.latencyMs = draft.latencyMs();
		log.costMicro = draft.costMicro();
		log.safetyFlags = (draft.safetyFlags() != null) ? draft.safetyFlags() : "[]";
		log.attemptNo = draft.attemptNo();
		log.createdAt = now;
		return log;
	}

	public UUID getId() {
		return this.id;
	}

	public UUID getSessionId() {
		return this.sessionId;
	}

	public String getPurpose() {
		return this.purpose;
	}

	public String getProviderId() {
		return this.providerId;
	}

	public String getRequestRaw() {
		return this.requestRaw;
	}

	public String getResponseRaw() {
		return this.responseRaw;
	}

	public int getAttemptNo() {
		return this.attemptNo;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	/**
	 * 기록할 값 한 벌.
	 *
	 * <p><b>{@code playerRef} 를 담을 자리가 없다</b> (I-3). 엔티티와 같은 성질이며, 기록을
	 * 만드는 쪽에서도 그 값을 실을 방법이 없다.
	 */
	public record Draft(
			UUID sessionId,
			UUID draftId,
			String purpose,
			String providerId,
			String modelId,
			String fallbackFrom,
			String requestRaw,
			String responseRaw,
			Integer inputTokens,
			Integer outputTokens,
			Integer latencyMs,
			Long costMicro,
			String safetyFlags,
			int attemptNo) {

		public Draft {
			if (purpose == null || purpose.isBlank()) {
				throw new IllegalArgumentException("purpose is required");
			}
			if (providerId == null || providerId.isBlank()) {
				throw new IllegalArgumentException("providerId is required");
			}
			if (modelId == null || modelId.isBlank()) {
				throw new IllegalArgumentException("modelId is required");
			}
			if (requestRaw == null) {
				// 응답은 없을 수 있어도 요청은 언제나 있다 — 무엇을 보냈는지가 단서다.
				throw new IllegalArgumentException("requestRaw is required");
			}
			if (attemptNo < 1) {
				throw new IllegalArgumentException("attemptNo starts at 1");
			}
		}
	}
}

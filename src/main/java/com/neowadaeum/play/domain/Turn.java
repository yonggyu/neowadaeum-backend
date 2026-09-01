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
 * 한 턴 — 본문 문단과 선택지 (§4.3).
 *
 * <p><b>I-1 — {@code choiceId} 는 서버가 발급한다.</b> 선택지는 {@code choices} 안에서만 존재하고,
 * 다음 요청의 {@code choiceId} 는 직전 턴의 이 값과 대조된다(§4.3-2). 클라이언트가 보낸 {@code text}
 * 는 어떤 경우에도 신뢰하지 않는다. 발급 규칙 자체는 S-9(B-21)의 범위다.
 *
 * <p><b>세션 참조를 {@code @ManyToOne} 으로 두지 않는다.</b> 이 스토어에서 턴은 세션 없이 의미가 없지만,
 * 조회 한 번이 연관 그래프를 끌고 오는 것을 기본값으로 만들 이유도 없다. 무결성은 같은 스키마 안의
 * FK 가 보장한다.
 *
 * <p>본문을 {@code story} 라 부르지 않는다 — {@code paragraphs} 다 (§3.5 금지 동의어).
 */
@Entity
@Table(name = "turn")
public class Turn {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "session_id", nullable = false, updatable = false)
	private UUID sessionId;

	/** 요청 {@code turnNo} 는 화면에 떠 있는 턴, 응답은 그 +1 이다 (§4.3 턴 번호 계약). */
	@Column(name = "turn_no", nullable = false, updatable = false)
	private int turnNo;

	@Column(name = "chapter_no", nullable = false, updatable = false)
	private int chapterNo;

	/** 본문 문단 배열 JSON. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "paragraphs", nullable = false, updatable = false)
	private String paragraphs;

	/** 서버가 발급한 선택지 배열 JSON. {@code disabled} 판정도 서버 몫이다 (I-11). */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "choices", nullable = false, updatable = false)
	private String choices;

	/** 사용자가 고른 선택지. 비어 있고 마지막 턴이면 {@code isPending} 이다 (§13-9). */
	@Column(name = "chosen_choice_id")
	private String chosenChoiceId;

	@Column(name = "chosen_at")
	private Instant chosenAt;

	/** 엔딩 턴이면 선택지가 비고 세션이 종료된다 (§4.6). 판정은 서버 단독이다 (I-10). */
	@Column(name = "is_ending", nullable = false, updatable = false)
	private boolean ending;

	/** 도달한 엔딩. catalog 스키마이므로 FK 없이 UUID 만 갖는다 (§5.3). */
	@Column(name = "ending_id", updatable = false)
	private UUID endingId;

	/** 이 턴에서 챕터가 바뀌었다. 클라이언트가 인터스티셜을 삽입하는 근거다 (R7.3). */
	@Column(name = "chapter_changed", nullable = false, updatable = false)
	private boolean chapterChanged;

	/**
	 * Safety L2 판정 (R9.3).
	 *
	 * <p><b>I-2 의 기록이다.</b> 기본값이 없다 — 판정 없이 턴을 만들 수 없게 하기 위해서다.
	 * {@code null} 을 허용하면 "검수받지 않음"과 "통과함"이 구분되지 않는다.
	 */
	@Column(name = "safety_verdict", nullable = false, updatable = false)
	private SafetyVerdict safetyVerdict;

	/**
	 * 관리자 자유입력으로 생성된 턴 (R14.2).
	 *
	 * <p><b>I-18 — 자유입력은 {@code is_test_session = true} 인 세션에서만 허용된다.</b> 그 규칙이
	 * 지켜졌는지는 이 표시가 있어야 사후에 확인할 수 있다. 교차 행 조건이라 DB CHECK 로는 막지
	 * 못하고, 현재는 애플리케이션이 책임진다 (S-9 에서 재검토).
	 */
	@Column(name = "is_admin_free_input", nullable = false, updatable = false)
	private boolean adminFreeInput;

	/**
	 * 이 턴의 본문이 AI 가 만든 것인가 (R11.2, §11).
	 *
	 * <p><b>응답을 만들 때 계산하지 않는다.</b> 계산하면 그 값은 응답 코드의 성질이지 이 턴의
	 * 사실이 아니고, 기록(B-35)이나 롤백 후 재생(R14.4)에서 같은 턴이 다른 값을 갖게 된다.
	 */
	@Column(name = "is_ai_generated", nullable = false, updatable = false)
	private boolean aiGenerated;

	/** 화자 이름. 표시 전용이며 값이 없을 수 있다 (§5.2). */
	@Column(name = "speaker_name", updatable = false)
	private String speakerName;

	@Column(name = "scene_image_url", updatable = false)
	private String sceneImageUrl;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	/** 롤백으로 되돌려진 시각 (R14.4). 지우지 않고 표시만 한다 — 유일성도 살아 있는 행 기준이다. */
	@Column(name = "deleted_at")
	private Instant deletedAt;

	protected Turn() {
	}

	/**
	 * 생성된 턴을 기록한다. 선택은 다음 요청에서 채워진다(§4.3-3) — 여기서는 비어 있다.
	 *
	 * <p>인자를 {@link TurnDraft} 로 묶은 이유는 열 개 가까운 값을 나열하면 <b>순서를 잘못 넣어도
	 * 컴파일이 통과하기 때문</b>이다. 같은 타입이 여럿 있는 자리에서 그 실수는 조용하다.
	 */
	public static Turn create(TurnDraft draft, Instant now) {
		if (draft == null) {
			throw new IllegalArgumentException("draft is required");
		}
		Turn turn = new Turn();
		turn.sessionId = draft.sessionId();
		turn.turnNo = draft.turnNo();
		turn.chapterNo = draft.chapterNo();
		turn.paragraphs = draft.paragraphs();
		turn.choices = draft.choices();
		turn.speakerName = draft.speakerName();
		turn.chapterChanged = draft.chapterChanged();
		turn.ending = draft.ending();
		turn.endingId = draft.endingId();
		turn.safetyVerdict = draft.safetyVerdict();
		turn.aiGenerated = draft.aiGenerated();
		turn.adminFreeInput = draft.adminFreeInput();
		turn.createdAt = now;
		return turn;
	}

	/**
	 * 턴 생성 입력 (§4.3-11).
	 *
	 * <p><b>{@code safetyVerdict} 에 기본값이 없다.</b> 판정 없이 턴을 만들 수 없게 하기 위해서다 —
	 * 턴은 §4.3-7 의 L2 이후에만 저장되므로 이 시점에 판정은 이미 정해져 있다 (I-2, R9.3).
	 */
	public record TurnDraft(
			UUID sessionId,
			int turnNo,
			int chapterNo,
			String paragraphs,
			String choices,
			String speakerName,
			boolean chapterChanged,
			boolean ending,
			UUID endingId,
			SafetyVerdict safetyVerdict,
			boolean aiGenerated,

			/**
			 * 관리자 자유입력으로 만들어진 턴인가 (R14.2).
			 *
			 * <p><b>본문을 만든 것은 여전히 AI 다.</b> 이 값이 표시하는 것은 <b>이번 턴의
			 * 행동이 어디서 왔는가</b>이며, {@code aiGenerated} 와 다른 축이다.
			 */
			boolean adminFreeInput) {

		public TurnDraft {
			if (safetyVerdict == null) {
				throw new IllegalArgumentException("safetyVerdict is required (I-2, R9.3)");
			}
			if (ending == (endingId == null)) {
				throw new IllegalArgumentException("an ending turn carries its ending id, and only it does (R7.8)");
			}
		}
	}

	/**
	 * 되돌리기로 접힌다 (R14.4, B-42).
	 *
	 * <p><b>지우지 않는다.</b> 되돌린 턴도 일어난 일이며, 무엇을 어디까지 되돌렸는지는
	 * 그 행이 남아 있어야 알 수 있다 (I-5 와 같은 성질).
	 *
	 * <p>두 번 접어도 처음 접힌 시각을 지킨다 — 되돌리기가 두 번 오면 두 번째가 첫 번째의
	 * 기록을 덮어써서는 안 된다.
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

	public int getChapterNo() {
		return this.chapterNo;
	}

	public String getParagraphs() {
		return this.paragraphs;
	}

	public String getChoices() {
		return this.choices;
	}

	/**
	 * 이 턴에서 무엇을 골랐는지 기록한다 (§4.3-3).
	 *
	 * <p><b>선택과 시각이 함께 간다.</b> 마이그레이션의 CHECK 가 둘 중 하나만 있는 상태를
	 * 거부하며(V2), {@code isPending} 판정(§13-9)과 기록 화면(§13.6)이 이 값을 읽는다.
	 *
	 * <p>이미 기록된 턴은 바뀌지 않는다 — 지나간 선택을 나중에 고치는 경로를 만들지 않는다.
	 */
	public void recordChoice(String choiceId, Instant now) {
		if (this.chosenChoiceId != null) {
			return;
		}
		this.chosenChoiceId = choiceId;
		this.chosenAt = now;
	}

	public String getChosenChoiceId() {
		return this.chosenChoiceId;
	}

	public Instant getChosenAt() {
		return this.chosenAt;
	}

	public boolean isEnding() {
		return this.ending;
	}

	public UUID getEndingId() {
		return this.endingId;
	}

	public boolean isChapterChanged() {
		return this.chapterChanged;
	}

	public SafetyVerdict getSafetyVerdict() {
		return this.safetyVerdict;
	}

	/** R11.2 — 이 턴이 만들어진 경로의 사실이다. */
	public boolean isAiGenerated() {
		return this.aiGenerated;
	}

	public boolean isAdminFreeInput() {
		return this.adminFreeInput;
	}

	public String getSpeakerName() {
		return this.speakerName;
	}

	public String getSceneImageUrl() {
		return this.sceneImageUrl;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public Instant getDeletedAt() {
		return this.deletedAt;
	}
}

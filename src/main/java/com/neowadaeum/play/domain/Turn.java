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
	 * <p><b>{@code safetyVerdict} 는 인자다.</b> 기본값을 두면 검수를 건너뛴 경로가 조용히 통과로
	 * 기록된다. 턴은 §4.3-7 의 L2 이후에만 저장되므로 이 시점에 판정은 이미 정해져 있다 (I-2).
	 */
	public static Turn create(UUID sessionId, int turnNo, int chapterNo, String paragraphs, String choices,
			boolean ending, SafetyVerdict safetyVerdict, Instant now) {
		if (safetyVerdict == null) {
			throw new IllegalArgumentException("safetyVerdict is required (I-2, R9.3)");
		}
		Turn turn = new Turn();
		turn.sessionId = sessionId;
		turn.turnNo = turnNo;
		turn.chapterNo = chapterNo;
		turn.paragraphs = paragraphs;
		turn.choices = choices;
		turn.ending = ending;
		turn.safetyVerdict = safetyVerdict;
		turn.createdAt = now;
		return turn;
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

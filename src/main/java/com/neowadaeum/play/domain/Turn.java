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

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected Turn() {
	}

	/** 생성된 턴을 기록한다. 선택은 다음 요청에서 채워진다(§4.3-3) — 여기서는 비어 있다. */
	public static Turn create(UUID sessionId, int turnNo, int chapterNo, String paragraphs, String choices,
			boolean ending, Instant now) {
		Turn turn = new Turn();
		turn.sessionId = sessionId;
		turn.turnNo = turnNo;
		turn.chapterNo = chapterNo;
		turn.paragraphs = paragraphs;
		turn.choices = choices;
		turn.ending = ending;
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

	public Instant getCreatedAt() {
		return this.createdAt;
	}
}

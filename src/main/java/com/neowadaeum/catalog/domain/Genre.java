package com.neowadaeum.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * 작품 분류 (§2.7).
 *
 * <p><b>{@code genreKey} 와 {@code label} 을 나눈 이유</b>는 화면 문구를 코드에 하드코딩하지 않기
 * 위해서다. API 와 필터는 {@code genreKey} 를 쓰고, 사람이 읽는 문구는 {@code label} 이 갖는다 —
 * 문구를 고치는 일이 배포가 되지 않는다.
 *
 * <p>컬럼 이름은 §2.7 원문 그대로 {@code key} 다. 필드 이름만 다른 것은 <b>JPQL 에서 {@code key}
 * 가 예약어</b>이기 때문이다({@code KEY()} 는 맵 키 함수다).
 */
@Entity
@Table(name = "genre")
public class Genre {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	/** API 표기. 유일하다 — 아니면 필터가 두 장르를 같은 이름으로 가리킨다. */
	@Column(name = "key", nullable = false, updatable = false)
	private String genreKey;

	/** 화면 문구. 바뀔 수 있다. */
	@Column(name = "label", nullable = false)
	private String label;

	/** 목록 노출 순서. 유일하므로 정렬이 결정론이다. */
	@Column(name = "display_order", nullable = false)
	private int displayOrder;

	protected Genre() {
	}

	public static Genre of(String genreKey, String label, int displayOrder) {
		if (genreKey == null || genreKey.isBlank() || label == null || label.isBlank()) {
			throw new IllegalArgumentException("genreKey, label are required");
		}
		Genre genre = new Genre();
		genre.genreKey = genreKey;
		genre.label = label;
		genre.displayOrder = displayOrder;
		return genre;
	}

	public UUID getId() {
		return this.id;
	}

	public String getGenreKey() {
		return this.genreKey;
	}

	public String getLabel() {
		return this.label;
	}

	public int getDisplayOrder() {
		return this.displayOrder;
	}
}

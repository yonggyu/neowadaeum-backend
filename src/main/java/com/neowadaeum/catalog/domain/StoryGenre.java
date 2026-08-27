package com.neowadaeum.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * 작품 ↔ 장르 (§2.7). 한 작품이 여러 장르를 갖는다.
 *
 * <p><b>연관이 아니라 식별자 두 개를 든다.</b> {@code @ManyToMany} 로 묶으면 작품 하나를 읽을
 * 때마다 장르가 딸려 오고, 반대로 장르 목록을 읽을 때 작품이 딸려 온다. 이 표는 <b>필터가 쓰는
 * 색인</b>이지 객체 그래프가 아니다.
 *
 * <p>두 컬럼이 함께 PK 이므로 같은 짝이 두 번 들어가지 않는다.
 */
@Entity
@Table(name = "story_genre")
@IdClass(StoryGenre.Key.class)
public class StoryGenre {

	@Id
	@Column(name = "story_id", nullable = false, updatable = false)
	private UUID storyId;

	@Id
	@Column(name = "genre_id", nullable = false, updatable = false)
	private UUID genreId;

	protected StoryGenre() {
	}

	public static StoryGenre of(UUID storyId, UUID genreId) {
		if (storyId == null || genreId == null) {
			throw new IllegalArgumentException("storyId, genreId are required");
		}
		StoryGenre link = new StoryGenre();
		link.storyId = storyId;
		link.genreId = genreId;
		return link;
	}

	public UUID getStoryId() {
		return this.storyId;
	}

	public UUID getGenreId() {
		return this.genreId;
	}

	/** 복합 PK. JPA 는 {@code @IdClass} 에 인자 없는 생성자와 값 동등성을 요구한다. */
	public static class Key implements Serializable {

		private UUID storyId;

		private UUID genreId;

		public Key() {
		}

		public Key(UUID storyId, UUID genreId) {
			this.storyId = storyId;
			this.genreId = genreId;
		}

		@Override
		public boolean equals(Object other) {
			return (other instanceof Key key) && Objects.equals(this.storyId, key.storyId)
					&& Objects.equals(this.genreId, key.genreId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.storyId, this.genreId);
		}
	}
}

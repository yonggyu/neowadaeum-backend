package com.neowadaeum.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.catalog.repository.GenreRepository;
import com.neowadaeum.catalog.repository.StoryGenreRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * B-08(1/2) — {@code genre} · {@code story_genre} 가 {@code catalog} 스키마에 실제로 매핑되는지.
 *
 * <p>매핑 자체는 {@code hibernate.hbm2ddl.auto=validate} 가 부팅에서 이미 본다. 여기서 보는 것은
 * <b>값이 왕복하는가</b>와 <b>DB 가 규칙을 실제로 거부하는가</b>다.
 *
 * <p><b>시드 작품을 빌려 쓴다.</b> {@code story_genre.story_id} 에 FK 가 걸려 있어 실재하는 작품이
 * 필요하고, {@code story} 는 아직 엔티티가 없다(S-4 가 시드로 넣었다). 링크는 테스트마다 지운다 —
 * 컨텍스트가 한 벌이라 남기면 다음 테스트가 그것을 본다.
 */
class CatalogGenreMappingTests extends ContainerTestBase {

	/** S-4 시드가 넣은 공식 작품. {@code CatalogSeedTests} 와 같은 값이다. */
	private static final UUID SEED_STORY = UUID.fromString("11111111-1111-4111-8111-000000000001");

	@Autowired
	private GenreRepository genres;

	@Autowired
	private StoryGenreRepository storyGenres;

	@AfterEach
	void clear() {
		this.storyGenres.deleteAll();
		this.genres.deleteAll();
	}

	/** §2.7 — 장르가 왕복한다. {@code key} 컬럼과 {@code genreKey} 필드가 이어져 있다. */
	@Test
	void S2_7_genre_round_trips_with_its_api_key_and_label() {
		Genre saved = this.genres.save(Genre.of("romance", "로맨스", 1));
		Genre found = this.genres.findById(saved.getId()).orElseThrow();

		assertThat(found.getGenreKey()).isEqualTo("romance");
		assertThat(found.getLabel()).isEqualTo("로맨스");
		assertThat(found.getDisplayOrder()).isEqualTo(1);
	}

	/**
	 * <b>{@code key} 는 JPQL 예약어다.</b> 필드 이름을 {@code genreKey} 로 둔 이유가 이것이며,
	 * 파생 조회가 실제로 성립하는지 여기서 확인한다 — 이름을 {@code key} 로 되돌리면 깨진다.
	 */
	@Test
	void S2_7_a_genre_is_findable_by_its_api_key() {
		this.genres.save(Genre.of("mystery", "미스터리", 2));

		assertThat(this.genres.findByGenreKey("mystery")).get().extracting(Genre::getLabel).isEqualTo("미스터리");
		assertThat(this.genres.findByGenreKey("nope")).isEmpty();
	}

	/** API 필터가 두 장르를 같은 이름으로 가리키지 못한다. */
	@Test
	void S2_7_genre_key_is_unique() {
		this.genres.saveAndFlush(Genre.of("thriller", "스릴러", 3));

		assertThatThrownBy(() -> this.genres.saveAndFlush(Genre.of("thriller", "스릴러2", 4)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	/** 목록 순서가 결정론이다 — {@code display_order} 가 유일하므로 동률이 없다 (B-15). */
	@Test
	void S2_7_genres_come_back_in_display_order() {
		this.genres.save(Genre.of("c", "다", 3));
		this.genres.save(Genre.of("a", "가", 1));
		this.genres.save(Genre.of("b", "나", 2));

		assertThat(this.genres.findAllByOrderByDisplayOrderAsc())
				.extracting(Genre::getGenreKey)
				.containsExactly("a", "b", "c");
	}

	/** §2.7 — 한 작품이 여러 장르를 갖고, 양방향으로 읽힌다 (B-15 · B-16). */
	@Test
	void S2_7_a_story_links_to_many_genres_and_both_directions_read() {
		Genre romance = this.genres.save(Genre.of("romance", "로맨스", 1));
		Genre school = this.genres.save(Genre.of("school", "학원", 2));
		this.storyGenres.save(StoryGenre.of(SEED_STORY, romance.getId()));
		this.storyGenres.save(StoryGenre.of(SEED_STORY, school.getId()));

		assertThat(this.storyGenres.findByStoryId(SEED_STORY))
				.extracting(StoryGenre::getGenreId)
				.containsExactlyInAnyOrder(romance.getId(), school.getId());
		assertThat(this.storyGenres.findByGenreId(romance.getId()))
				.extracting(StoryGenre::getStoryId)
				.containsExactly(SEED_STORY);
	}

	/**
	 * <b>같은 짝이 두 번 들어가지 않는다.</b>
	 *
	 * <p>복합 PK 이므로 두 번째 저장은 삽입이 아니라 <b>병합</b>이다 — 행이 늘지 않는다.
	 * 중복 링크가 쌓이면 목록에 같은 작품이 여러 번 나온다.
	 */
	@Test
	void S2_7_the_same_story_genre_pair_cannot_be_stored_twice() {
		Genre genre = this.genres.save(Genre.of("sf", "SF", 1));
		this.storyGenres.save(StoryGenre.of(SEED_STORY, genre.getId()));
		this.storyGenres.save(StoryGenre.of(SEED_STORY, genre.getId()));

		assertThat(this.storyGenres.findByStoryId(SEED_STORY)).hasSize(1);
	}

	/** §5.3 — 존재하지 않는 작품에 장르를 붙일 수 없다. FK 는 catalog 안에 있으므로 DB 가 막는다. */
	@Test
	void S5_3_a_link_to_an_unknown_story_is_rejected_by_the_database() {
		Genre genre = this.genres.save(Genre.of("horror", "호러", 1));
		List<StoryGenre> orphan = List.of(StoryGenre.of(UUID.randomUUID(), genre.getId()));

		assertThatThrownBy(() -> this.storyGenres.saveAllAndFlush(orphan))
				.isInstanceOf(DataIntegrityViolationException.class);
	}
}

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
 * 필요하고, {@code story} 는 아직 엔티티가 없다(S-4 가 시드로 넣었다).
 *
 * <p><b>시드 장르를 지우지 않는다</b> (B-15, V9). 예전에는 {@code deleteAll()} 로 비웠고 그때는
 * 표가 비어 있었다. 이제 라이브러리가 그 다섯 행 위에서 돌므로, <b>이 테스트가 만든 것만</b>
 * 지운다 — 공유 데이터를 비우는 테스트는 실행 순서에 따라 남을 무너뜨린다.
 *
 * <p>같은 이유로 단언도 <b>상대적</b>이다. "정확히 이것뿐"이 아니라 "이것이 있다"를 본다.
 */
class CatalogGenreMappingTests extends ContainerTestBase {

	/** S-4 시드가 넣은 공식 작품. {@code CatalogSeedTests} 와 같은 값이다. */
	private static final UUID SEED_STORY = UUID.fromString("11111111-1111-4111-8111-000000000001");

	@Autowired
	private GenreRepository genres;

	@Autowired
	private StoryGenreRepository storyGenres;

	/** 이 테스트가 만든 장르. 시드(V9)는 건드리지 않는다. */
	private final List<Genre> created = new java.util.ArrayList<>();

	@AfterEach
	void clear() {
		this.created.forEach(genre -> {
			this.storyGenres.findByGenreId(genre.getId()).forEach(this.storyGenres::delete);
			this.genres.delete(genre);
		});
		this.created.clear();
	}

	/**
	 * 시드와 부딪히지 않는 장르를 만든다.
	 *
	 * <p>{@code key} 와 {@code display_order} 가 둘 다 UNIQUE 다 — 시드가 1~5 를 쓰므로
	 * 그 위에서 시작한다.
	 */
	private Genre newGenre(String key, String label, int displayOrder) {
		Genre genre = this.genres.save(Genre.of("test-" + key, label, 100 + displayOrder));
		this.created.add(genre);
		return genre;
	}

	/** §2.7 — 장르가 왕복한다. {@code key} 컬럼과 {@code genreKey} 필드가 이어져 있다. */
	@Test
	void S2_7_genre_round_trips_with_its_api_key_and_label() {
		Genre saved = newGenre("romance", "로맨스", 1);
		Genre found = this.genres.findById(saved.getId()).orElseThrow();

		assertThat(found.getGenreKey()).isEqualTo("test-romance");
		assertThat(found.getLabel()).isEqualTo("로맨스");
		assertThat(found.getDisplayOrder()).isEqualTo(101);
	}

	/**
	 * <b>{@code key} 는 JPQL 예약어다.</b> 필드 이름을 {@code genreKey} 로 둔 이유가 이것이며,
	 * 파생 조회가 실제로 성립하는지 여기서 확인한다 — 이름을 {@code key} 로 되돌리면 깨진다.
	 */
	@Test
	void S2_7_a_genre_is_findable_by_its_api_key() {
		newGenre("mystery", "미스터리", 2);

		assertThat(this.genres.findByGenreKey("test-mystery")).get()
				.extracting(Genre::getLabel).isEqualTo("미스터리");
		assertThat(this.genres.findByGenreKey("nope")).isEmpty();
	}

	/** API 필터가 두 장르를 같은 이름으로 가리키지 못한다. */
	@Test
	void S2_7_genre_key_is_unique() {
		newGenre("thriller", "스릴러", 3);

		assertThatThrownBy(() -> this.genres.saveAndFlush(Genre.of("test-thriller", "스릴러2", 104)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	/** 목록 순서가 결정론이다 — {@code display_order} 가 유일하므로 동률이 없다 (B-15). */
	@Test
	void S2_7_genres_come_back_in_display_order() {
		newGenre("c", "다", 3);
		newGenre("a", "가", 1);
		newGenre("b", "나", 2);

		// 시드 다섯이 앞에 있으므로 "정확히 이 셋"이 아니라 "이 셋의 순서"를 본다.
		assertThat(this.genres.findAllByOrderByDisplayOrderAsc())
				.extracting(Genre::getGenreKey)
				.filteredOn(key -> key.startsWith("test-"))
				.containsExactly("test-a", "test-b", "test-c");
	}

	/** §2.7 — 한 작품이 여러 장르를 갖고, 양방향으로 읽힌다 (B-15 · B-16). */
	@Test
	void S2_7_a_story_links_to_many_genres_and_both_directions_read() {
		Genre romance = newGenre("romance", "로맨스", 1);
		Genre school = newGenre("school", "학원", 2);
		this.storyGenres.save(StoryGenre.of(SEED_STORY, romance.getId()));
		this.storyGenres.save(StoryGenre.of(SEED_STORY, school.getId()));

		// 시드가 이미 두 장르를 붙여 뒀다 (V9). "정확히 둘"이 아니라 "둘이 있다"를 본다.
		assertThat(this.storyGenres.findByStoryId(SEED_STORY))
				.extracting(StoryGenre::getGenreId)
				.contains(romance.getId(), school.getId());
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
		Genre genre = newGenre("sf", "SF", 1);
		this.storyGenres.save(StoryGenre.of(SEED_STORY, genre.getId()));
		this.storyGenres.save(StoryGenre.of(SEED_STORY, genre.getId()));

		assertThat(this.storyGenres.findByGenreId(genre.getId())).hasSize(1);
	}

	/** §5.3 — 존재하지 않는 작품에 장르를 붙일 수 없다. FK 는 catalog 안에 있으므로 DB 가 막는다. */
	@Test
	void S5_3_a_link_to_an_unknown_story_is_rejected_by_the_database() {
		Genre genre = newGenre("horror", "호러", 1);
		List<StoryGenre> orphan = List.of(StoryGenre.of(UUID.randomUUID(), genre.getId()));

		assertThatThrownBy(() -> this.storyGenres.saveAllAndFlush(orphan))
				.isInstanceOf(DataIntegrityViolationException.class);
	}
}

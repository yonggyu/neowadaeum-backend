package com.neowadaeum.catalog.repository;

import com.neowadaeum.catalog.domain.StoryGenre;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 작품 ↔ 장르 영속화 (§2.7).
 *
 * <p>두 방향을 모두 읽는다 — 작품 상세는 "이 작품의 장르"(B-16)를, 라이브러리 필터는
 * "이 장르의 작품"(B-15)을 묻는다. PK 는 앞 방향만 커버하므로 뒤 방향에 색인이 따로 있다.
 */
public interface StoryGenreRepository extends JpaRepository<StoryGenre, StoryGenre.Key> {

	List<StoryGenre> findByStoryId(UUID storyId);

	List<StoryGenre> findByGenreId(UUID genreId);
}

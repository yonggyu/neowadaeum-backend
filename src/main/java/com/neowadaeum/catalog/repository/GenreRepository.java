package com.neowadaeum.catalog.repository;

import com.neowadaeum.catalog.domain.Genre;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 장르 영속화 (§2.7).
 *
 * <p>이 리포지터리는 {@code catalogEntityManagerFactory} 에만 묶인다. 스캔 범위가
 * {@code com.neowadaeum.catalog} 로 고정되어 있어 다른 스토어의 엔티티를 볼 수 없다 (§5.3).
 */
public interface GenreRepository extends JpaRepository<Genre, UUID> {

	/** 화면 노출 순서대로. {@code display_order} 가 유일하므로 결정론이다 (B-15). */
	List<Genre> findAllByOrderByDisplayOrderAsc();

	Optional<Genre> findByGenreKey(String genreKey);
}

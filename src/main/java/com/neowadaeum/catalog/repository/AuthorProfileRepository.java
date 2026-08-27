package com.neowadaeum.catalog.repository;

import com.neowadaeum.catalog.domain.AuthorProfile;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 작성자 표시명 영속화 (§13-7).
 *
 * <p>목록 화면은 작품마다 작성자를 하나씩 묻지 않는다 — {@code playerRef} 를 모아 한 번에
 * 읽는다. 작품 수만큼 조회가 나가면 라이브러리의 p95 300ms 를 그것만으로 넘긴다 (B-15).
 */
public interface AuthorProfileRepository extends JpaRepository<AuthorProfile, UUID> {

	List<AuthorProfile> findByPlayerRefIn(Iterable<UUID> playerRefs);
}

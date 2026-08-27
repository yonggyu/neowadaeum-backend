package com.neowadaeum.identity.repository;

import com.neowadaeum.identity.domain.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 회원 영속화 (§2.2).
 *
 * <p>이 리포지터리는 {@code identityEntityManagerFactory} 에만 묶인다. 스캔 범위가
 * {@code com.neowadaeum.identity} 로 고정되어 있어 다른 스토어의 엔티티를 볼 수 없다 —
 * 크로스 스키마 조인이 <b>쓰지 않기로 한 것</b>이 아니라 <b>쓸 수 없는 것</b>이 된다 (§5.3).
 */
public interface UserRepository extends JpaRepository<User, UUID> {

	/** 다른 스토어에서 돌아오는 유일한 경로다 — {@code user.id} 는 밖으로 나가지 않는다 (I-3). */
	Optional<User> findByPlayerRef(UUID playerRef);
}

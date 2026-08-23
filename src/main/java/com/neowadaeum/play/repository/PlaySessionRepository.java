package com.neowadaeum.play.repository;

import com.neowadaeum.play.domain.PlaySession;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 세션 영속화 (§9.2 — Repository 는 영속화만 한다).
 *
 * <p>이 리포지터리는 {@code playEntityManagerFactory} 에만 묶인다. 스캔 범위가
 * {@code com.neowadaeum.play} 로 고정되어 있어(config) 다른 스토어의 엔티티를 볼 수 없다 — 크로스 스키마
 * 조인이 <b>쓰지 않기로 한 것</b>이 아니라 <b>쓸 수 없는 것</b>이 되는 지점이다 (§5.3).
 */
public interface PlaySessionRepository extends JpaRepository<PlaySession, UUID> {

}

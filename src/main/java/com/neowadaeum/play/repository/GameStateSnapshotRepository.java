package com.neowadaeum.play.repository;

import com.neowadaeum.play.domain.GameStateSnapshot;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 스냅샷 영속화 (§9.2).
 *
 * <p><b>I-5 — append-only.</b> {@link JpaRepository#save} 는 새 스냅샷을 추가하는 데만 쓴다.
 * 기존 행을 불러 고쳐 저장하는 경로를 만들지 않는다 — 그 순간 롤백할 대상이 사라진다.
 */
public interface GameStateSnapshotRepository extends JpaRepository<GameStateSnapshot, UUID> {

}

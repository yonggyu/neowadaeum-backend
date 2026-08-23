package com.neowadaeum.play.repository;

import com.neowadaeum.play.domain.Turn;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 턴 영속화 (§9.2). 조회 API(B-35 History)의 커서 페이지네이션은 그 작업에서 더한다. */
public interface TurnRepository extends JpaRepository<Turn, UUID> {

}

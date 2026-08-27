package com.neowadaeum.catalog.repository;

import com.neowadaeum.catalog.domain.EndingStat;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 엔딩 도달률 원자료 영속화 (§2.6).
 *
 * <p><b>I-20 — 여기에 실시간 계산 경로를 두지 않는다.</b> 값을 만드는 것은 배치(B-39)이고
 * 이 인터페이스는 그 결과를 읽고 쓸 뿐이다. 조회 API 는 R2.8 의 임계값을 적용해 노출 여부를
 * 정한다 — 그 판정도 여기가 아니다.
 */
public interface EndingStatRepository extends JpaRepository<EndingStat, EndingStat.Key> {

	List<EndingStat> findByStoryId(UUID storyId);
}

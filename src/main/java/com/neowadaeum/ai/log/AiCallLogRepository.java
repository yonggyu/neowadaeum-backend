package com.neowadaeum.ai.log;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@code ai_call_log} 영속화 (B-11).
 *
 * <p><b>{@code promptlog} 스토어 전용이다.</b> 이 인터페이스는 {@code promptLogEntityManagerFactory}
 * 에 묶이며 ({@code PromptLogJpaConfiguration}), 다른 스토어의 엔티티를 참조하는 조회는 매핑
 * 단계에서 거부된다 — 그것이 §5.3 스키마 분리의 실질이다.
 *
 * <p>기록 파이프라인은 B-25 다. 이 인터페이스는 그때 쓰이며, 지금은 <b>스키마와 격리가 실재하는지</b>
 * 를 확인하는 자리다.
 */
public interface AiCallLogRepository extends JpaRepository<AiCallLog, UUID> {

	/** 세션 단위 역추적 (I-3). 회원이 아니라 세션으로만 찾는다. */
	List<AiCallLog> findBySessionIdOrderByCreatedAtDesc(UUID sessionId, Limit limit);

	/**
	 * 보관 기간이 지난 행을 지운다 (B-61).
	 *
	 * <p><b>벌크다.</b> 엔티티를 읽어 지우면 기간치를 전부 메모리에 올리게 된다 — 지우는 일에
	 * 필요한 것은 <b>조건</b>이지 행의 내용이 아니다.
	 */
	@org.springframework.data.jpa.repository.Modifying
	@org.springframework.data.jpa.repository.Query(
			"DELETE FROM AiCallLog l WHERE l.createdAt < :before")
	int deleteCreatedBefore(java.time.Instant before);
}

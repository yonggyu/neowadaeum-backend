package com.neowadaeum.identity.repository;

import com.neowadaeum.identity.domain.AiNoticeImpression;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * AI 고지 노출 이력 영속화 (R11.3).
 *
 * <p>기록 파이프라인은 B-14 다. 여기서는 저장과 <b>노출 여부 확인</b>만 필요하다 —
 * "이 판본을 이미 보여 줬는가"가 재노출 판정의 기준이 된다.
 */
public interface AiNoticeImpressionRepository extends JpaRepository<AiNoticeImpression, UUID> {

	boolean existsByUserIdAndNoticeVersion(UUID userId, String noticeVersion);
}

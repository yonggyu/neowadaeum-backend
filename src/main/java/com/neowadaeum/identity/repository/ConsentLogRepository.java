package com.neowadaeum.identity.repository;

import com.neowadaeum.identity.domain.ConsentLog;
import com.neowadaeum.identity.domain.ConsentType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 동의 이력 영속화 (R10.2).
 *
 * <p><b>append-only 다.</b> 갱신·삭제 메서드를 두지 않는다 — 필요한 질문은 언제나 "이 회원이
 * 이 종류에 <b>가장 최근</b> 동의한 판본이 무엇인가"이고, 그 답은 새 행을 읽는 것으로 나온다.
 */
public interface ConsentLogRepository extends JpaRepository<ConsentLog, UUID> {

	Optional<ConsentLog> findFirstByUserIdAndConsentTypeOrderByAgreedAtDesc(UUID userId,
			ConsentType consentType);

	/** 같은 종류에 몇 번 동의했는가. 재동의가 덮어쓰기가 아님을 확인하는 데 쓴다. */
	long countByUserIdAndConsentType(UUID userId, ConsentType consentType);
}

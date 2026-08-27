package com.neowadaeum.ai.log;

import com.neowadaeum.common.spi.AdminAuditRecorder;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link AdminAuditRecorder} 의 promptlog 쪽 구현 (R14.5, ADR-0002 와 같은 형태).
 *
 * <p><b>데이터를 소유한 모듈이 구현한다.</b> {@code admin_audit_log} 가 promptlog 스키마에 있고
 * (§2.7) 그 스토어의 EMF 를 가진 모듈은 여기뿐이다.
 *
 * <p><b>기록 실패가 호출자를 막지 않는다.</b> 감사를 붙인 대가로 관리자 기능이 멈추면 감사를
 * 떼게 된다 — {@code AiCallRecorder}(B-25)와 같은 판단이다. <b>다만 조용히 넘어가지 않는다</b>:
 * 감사가 남지 않은 관리자 행위는 그 자체가 사고이며 {@code error} 로 남는다.
 */
@Component
public class PromptLogAdminAuditRecorder implements AdminAuditRecorder {

	private static final Logger log = LoggerFactory.getLogger(PromptLogAdminAuditRecorder.class);

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final AdminAuditLogRepository logs;

	private final Clock clock;

	public PromptLogAdminAuditRecorder(AdminAuditLogRepository logs, Clock clock) {
		this.logs = logs;
		this.clock = clock;
	}

	@Override
	@Transactional("promptLogTransactionManager")
	public void record(UUID adminUserId, String action, String targetType, UUID targetId,
			Map<String, Object> payload, String ipHash) {
		try {
			this.logs.save(AdminAuditLog.record(adminUserId, action, targetType, targetId,
					JSON.writeValueAsString((payload != null) ? payload : Map.of()), ipHash,
					this.clock.instant()));
		}
		catch (RuntimeException ex) {
			// 내용을 로그에 옮기지 않는다 (S-3). 무엇이 실패했는지만 남긴다.
			log.error("admin.audit.failed action={} reason={}", action, ex.getClass().getSimpleName());
		}
	}
}

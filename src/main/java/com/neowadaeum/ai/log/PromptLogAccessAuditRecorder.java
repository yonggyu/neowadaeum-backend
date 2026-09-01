package com.neowadaeum.ai.log;

import com.neowadaeum.common.spi.AccessAuditRecorder;
import com.neowadaeum.common.spi.AuditedResource;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 열람 기록을 {@code promptlog} 에 남긴다 (R12.3, S-5).
 *
 * <p><b>실패를 삼키지 않는다.</b> {@code PromptLogAdminAuditRecorder} 는 기록에 실패해도 행위를
 * 막지 않지만 이쪽은 다르다 — 남기지 못하는 열람을 허용하면 <b>기록되지 않는 열람 경로</b>가
 * 생기고, 그것이 S-5 가 막으려는 상태다. 예외는 그대로 올라가 <b>읽기 자체를 실패시킨다.</b>
 *
 * <p><b>기록이 원문보다 먼저다.</b> 부르는 쪽은 이 메서드가 끝난 뒤에 원문을 꺼낸다.
 */
@Component
public class PromptLogAccessAuditRecorder implements AccessAuditRecorder {

	private final AccessAuditLogRepository logs;

	private final Clock clock;

	public PromptLogAccessAuditRecorder(AccessAuditLogRepository logs, Clock clock) {
		this.logs = logs;
		this.clock = clock;
	}

	@Override
	@Transactional("promptLogTransactionManager")
	public void record(UUID adminUserId, AuditedResource resource, UUID resourceId) {
		this.logs.save(AccessAuditLog.of(adminUserId, resource, resourceId, this.clock.instant()));
	}
}

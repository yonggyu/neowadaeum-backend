package com.neowadaeum.ai.log;

import com.neowadaeum.common.spi.LogRetentionPurge;
import com.neowadaeum.common.support.RetentionProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code promptlog} 스토어의 보관 기간 파기 (R12.4, S-10, B-61).
 *
 * <p><b>세 표가 같은 스토어에 있고 기간이 다르다.</b> 프롬프트 로그는 <b>원문 보관처</b>이므로
 * 가장 짧고(R12.4), 감사 로그는 <b>사후 추적의 근거</b>이므로 가장 길다(S-10). 하나로 묶으면
 * 둘 중 하나를 바꿀 때 다른 하나가 함께 움직인다.
 *
 * <p><b>벌크 삭제다.</b> 엔티티를 읽어 지우면 90일치를 전부 메모리에 올리게 된다 — 지우는 일에
 * 필요한 것은 <b>조건</b>이지 행의 내용이 아니다.
 *
 * <p><b>한 트랜잭션이다.</b> 셋은 같은 스토어이고 같은 회차에 지워야 할 것들이며, 나누면 두
 * 번째에서 실패했을 때 <b>어디까지 지워졌는지</b>를 로그의 수치만으로는 알 수 없게 된다.
 */
@Service
public class PromptLogRetentionPurge implements LogRetentionPurge {

	private final AiCallLogRepository aiCallLogs;

	private final AdminAuditLogRepository adminAuditLogs;

	private final AccessAuditLogRepository accessAuditLogs;

	private final RetentionProperties retention;

	private final Clock clock;

	public PromptLogRetentionPurge(AiCallLogRepository aiCallLogs,
			AdminAuditLogRepository adminAuditLogs, AccessAuditLogRepository accessAuditLogs,
			RetentionProperties retention, Clock clock) {
		this.aiCallLogs = aiCallLogs;
		this.adminAuditLogs = adminAuditLogs;
		this.accessAuditLogs = accessAuditLogs;
		this.retention = retention;
		this.clock = clock;
	}

	@Override
	@Transactional("promptLogTransactionManager")
	public int purgeExpiredLogs() {
		Instant now = Instant.now(this.clock);
		Instant promptsBefore = now.minus(this.retention.promptLogRetention());
		// 년은 일로 환산하지 않는다 — 윤년이 있고, 3년은 "3년"이라고 적힌 값이다.
		Instant auditsBefore = now.atOffset(ZoneOffset.UTC)
				.minus(this.retention.auditLogRetention()).toInstant();

		return this.aiCallLogs.deleteCreatedBefore(promptsBefore)
				+ this.adminAuditLogs.deleteCreatedBefore(auditsBefore)
				+ this.accessAuditLogs.deleteCreatedBefore(auditsBefore);
	}
}

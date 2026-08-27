package com.neowadaeum.ai.debug;

import com.neowadaeum.ai.log.AiCallLog;
import com.neowadaeum.ai.log.AiCallLogRepository;
import com.neowadaeum.common.spi.AccessAuditRecorder;
import com.neowadaeum.common.spi.AuditedResource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

/**
 * 원문을 꺼내는 유일한 길 (§14 Debug, R12.3, S-5).
 *
 * <p><b>열람 기록을 이 안에서 한다.</b> 부르는 쪽에 맡기면 <b>기록을 잊은 호출자</b>가 생기고,
 * 그 하나가 곧 기록되지 않는 열람 경로다. 기록이 여기 있으면 <b>길이 하나뿐이라는 것 자체가
 * 보장</b>이 된다.
 *
 * <p><b>기록에 실패하면 원문은 나가지 않는다.</b> {@link AccessAuditRecorder} 가 예외를 그대로
 * 올리고, 이 메서드는 거기서 끝난다 — 읽어 둔 값은 응답으로 가지 못한다.
 *
 * <p><b>건마다 남긴다.</b> 다섯 건을 읽으면 다섯 줄이다. 묶어서 한 줄로 남기면 <b>무엇을
 * 봤는지</b>가 사라진다.
 */
@Service
public class AiCallLogFacade {

	private final AiCallLogRepository logs;

	private final AccessAuditRecorder access;

	public AiCallLogFacade(AiCallLogRepository logs, AccessAuditRecorder access) {
		this.logs = logs;
		this.access = access;
	}

	/**
	 * 한 세션의 최근 호출 원문.
	 *
	 * @param adminUserId 읽는 사람. <b>감사에 남길 대상이므로 필수다</b> — 없으면 읽지 않는다
	 */
	public List<AiCallView> recentCalls(UUID sessionId, UUID adminUserId, int limit) {
		if (adminUserId == null) {
			throw new IllegalArgumentException("원문 열람에는 행위자가 있어야 한다");
		}
		List<AiCallLog> rows = this.logs.findBySessionIdOrderByCreatedAtDesc(sessionId, Limit.of(limit));

		List<AiCallView> views = new ArrayList<>(rows.size());
		for (AiCallLog row : rows) {
			this.access.record(adminUserId, AuditedResource.AI_CALL_LOG, row.getId());
			views.add(viewOf(row));
		}
		return views;
	}

	private static AiCallView viewOf(AiCallLog row) {
		return new AiCallView(row.getId(), row.getPurpose(), row.getProviderId(), row.getModelId(),
				row.getFallbackFrom(), row.getRequestRaw(), row.getResponseRaw(), row.getInputTokens(),
				row.getOutputTokens(), row.getLatencyMs(), row.getCostMicro(), row.getSafetyFlags(),
				row.getAttemptNo(), row.getCreatedAt());
	}
}

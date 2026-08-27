package com.neowadaeum.ai.log;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.common.spi.AccessAuditRecorder;
import com.neowadaeum.common.spi.AuditedResource;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;

/**
 * B-41 — 원문을 읽은 사실이 {@code promptlog} 에 남는다 (R12.3, S-5, §2.7).
 *
 * <p>부르는 쪽은 어느 스토어에 남는지 모른다 — SPI 를 거친다 (ADR-0002 와 같은 형태).
 */
class AccessAuditLogIntegrationTests extends ContainerTestBase {

	@Autowired
	private AccessAuditRecorder recorder;

	@Autowired
	private AccessAuditLogRepository logs;

	@AfterEach
	void clear() {
		this.logs.deleteAll();
	}

	/** 읽은 사람·자원·대상이 함께 남는다. */
	@Test
	void R12_3_a_raw_read_is_recorded() {
		UUID adminUserId = UUID.randomUUID();
		UUID callLogId = UUID.randomUUID();

		this.recorder.record(adminUserId, AuditedResource.AI_CALL_LOG, callLogId);

		assertThat(this.logs.findByAdminUserIdOrderByCreatedAtDesc(adminUserId, Limit.of(10)))
				.singleElement()
				.satisfies(log -> {
					assertThat(log.getResource()).isEqualTo("ai_call_log");
					assertThat(log.getResourceId()).isEqualTo(callLogId);
					assertThat(log.getCreatedAt()).isNotNull();
				});
	}

	/** 무엇이 읽혔는지도 되짚을 수 있다 — 사고 조사는 이 방향으로 본다. */
	@Test
	void R12_3_a_resource_can_be_traced_back_to_its_readers() {
		UUID callLogId = UUID.randomUUID();
		this.recorder.record(UUID.randomUUID(), AuditedResource.AI_CALL_LOG, callLogId);
		this.recorder.record(UUID.randomUUID(), AuditedResource.AI_CALL_LOG, callLogId);

		assertThat(this.logs.findByResourceAndResourceIdOrderByCreatedAtDesc("ai_call_log", callLogId,
				Limit.of(10))).hasSize(2);
	}

	/** 검수 전 원고도 같은 표에 남는다 (I-8). CHECK 가 두 값만 받는다. */
	@Test
	void R12_3_a_draft_read_is_recorded_too() {
		UUID draftId = UUID.randomUUID();

		this.recorder.record(UUID.randomUUID(), AuditedResource.STORY_DRAFT, draftId);

		assertThat(this.logs.findByResourceAndResourceIdOrderByCreatedAtDesc("story_draft", draftId,
				Limit.of(10))).hasSize(1);
	}
}

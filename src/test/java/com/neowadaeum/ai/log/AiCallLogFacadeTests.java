package com.neowadaeum.ai.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.neowadaeum.common.spi.AccessAuditRecorder;
import com.neowadaeum.common.spi.AuditedResource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Limit;

/**
 * B-41 — <b>기록하지 못하면 읽지도 못한다</b> (R12.3, S-5).
 *
 * <p>원문을 꺼내는 길이 하나뿐이라는 것이 이 층의 설계다. 그 길 안에 기록이 있으므로
 * <b>기록을 잊은 호출자</b>가 생길 수 없다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class AiCallLogFacadeTests {

	private static final UUID SESSION_ID = UUID.randomUUID();

	private final AiCallLogRepository logs = mock(AiCallLogRepository.class);

	private final AccessAuditRecorder access = mock(AccessAuditRecorder.class);

	private final AiCallLogFacade facade = new AiCallLogFacade(this.logs, this.access);

	private final UUID adminUserId = UUID.randomUUID();

	/** <b>건마다 남는다.</b> 묶어서 한 줄로 남기면 무엇을 봤는지가 사라진다. */
	@Test
	void R12_3_every_raw_read_is_recorded_individually() {
		givenCalls(2);

		assertThat(this.facade.recentCalls(SESSION_ID, this.adminUserId, 10)).hasSize(2);

		ArgumentCaptor<UUID> read = ArgumentCaptor.forClass(UUID.class);
		verify(this.access, org.mockito.Mockito.times(2))
				.record(org.mockito.ArgumentMatchers.eq(this.adminUserId),
						org.mockito.ArgumentMatchers.eq(AuditedResource.AI_CALL_LOG), read.capture());
		assertThat(read.getAllValues()).doesNotHaveDuplicates();
	}

	/**
	 * <b>기록이 실패하면 원문은 나가지 않는다.</b>
	 *
	 * <p>기록 실패를 삼키고 원문을 돌려주면 <b>기록되지 않는 열람 경로</b>가 생긴다 — S-5 가
	 * 막으려는 상태가 정확히 그것이다.
	 */
	@Test
	void S5_a_failed_audit_blocks_the_read() {
		givenCalls(1);
		willThrow(new IllegalStateException("감사 기록 실패")).given(this.access)
				.record(any(), any(), any());

		assertThatThrownBy(() -> this.facade.recentCalls(SESSION_ID, this.adminUserId, 10))
				.isInstanceOf(IllegalStateException.class);
	}

	/** 행위자가 없으면 읽지 않는다 — 남길 대상이 없는 열람은 기록되지 않는 열람이다. */
	@Test
	void S5_an_anonymous_read_is_refused() {
		assertThatThrownBy(() -> this.facade.recentCalls(SESSION_ID, null, 10))
				.isInstanceOf(IllegalArgumentException.class);
	}

	/** 꺼낸 값이 원문과 사용량을 함께 들고 온다 (§14 Debug). */
	@Test
	void R14_5_the_view_carries_the_raw_text_and_the_usage() {
		givenCalls(1);

		AiCallView view = this.facade.recentCalls(SESSION_ID, this.adminUserId, 10).getFirst();

		assertThat(view.requestRaw()).isEqualTo("요청 원문");
		assertThat(view.responseRaw()).isEqualTo("응답 원문");
		assertThat(view.inputTokens()).isEqualTo(11);
		assertThat(view.outputTokens()).isEqualTo(22);
		assertThat(view.latencyMs()).isEqualTo(33);
		assertThat(view.costMicro()).isEqualTo(44L);
		assertThat(view.providerId()).isEqualTo("fixed");
		assertThat(view.modelId()).isEqualTo("scenario");
	}

	private void givenCalls(int count) {
		java.util.List<AiCallLog> rows = new java.util.ArrayList<>();
		for (int i = 0; i < count; i++) {
			AiCallLog row = AiCallLog.record(new AiCallLog.Draft(SESSION_ID, null, "turn", "fixed",
					"scenario", null, "요청 원문", "응답 원문", 11, 22, 33, 44L, "[]", 1), Instant.now());
			setId(row, UUID.randomUUID());
			rows.add(row);
		}
		given(this.logs.findBySessionIdOrderByCreatedAtDesc(org.mockito.ArgumentMatchers.eq(SESSION_ID),
				any(Limit.class))).willReturn(List.copyOf(rows));
	}

	/** {@code id} 는 DB 가 붙인다. 테스트에서만 미리 채운다. */
	private static void setId(AiCallLog log, UUID id) {
		try {
			java.lang.reflect.Field field = AiCallLog.class.getDeclaredField("id");
			field.setAccessible(true);
			field.set(log, id);
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
	}
}

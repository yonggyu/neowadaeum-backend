package com.neowadaeum.ai.log;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.common.spi.AdminAuditRecorder;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;

/**
 * B-40 — 관리자 행위가 <b>promptlog 에</b> 남는다 (R14.5, §2.7).
 *
 * <p>부르는 쪽({@code identity})은 그 스토어를 모른다 — SPI 를 거친다 (ADR-0002 와 같은 형태).
 */
class AdminAuditLogIntegrationTests extends ContainerTestBase {

	@Autowired
	private AdminAuditRecorder recorder;

	@Autowired
	private AdminAuditLogRepository logs;

	@AfterEach
	void clear() {
		this.logs.deleteAll();
	}

	/** R14.5 — 행위가 행위자·대상·맥락과 함께 남는다. */
	@Test
	void R14_5_an_admin_action_is_recorded_with_its_context() {
		UUID adminUserId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();

		this.recorder.record(adminUserId, "admin.debug.read", "session", sessionId,
				Map.of("reason", "support"), "ip-hash");

		assertThat(this.logs.findByAdminUserIdOrderByCreatedAtDesc(adminUserId, Limit.of(10)))
				.singleElement()
				.satisfies(log -> {
					assertThat(log.getAction()).isEqualTo("admin.debug.read");
					assertThat(log.getTargetType()).isEqualTo("session");
					assertThat(log.getTargetId()).isEqualTo(sessionId);
					assertThat(log.getPayload()).contains("support");
					assertThat(log.getIpHash()).isEqualTo("ip-hash");
					assertThat(log.getCreatedAt()).isNotNull();
				});
	}

	/** 대상이 없는 행위도 남는다 — 거절 시도가 그렇다. */
	@Test
	void R14_5_an_action_without_a_target_is_still_recorded() {
		UUID adminUserId = UUID.randomUUID();

		this.recorder.record(adminUserId, "admin.access.denied", "admin", null, Map.of("ipOk", false),
				"ip-hash");

		assertThat(this.logs.findByAdminUserIdOrderByCreatedAtDesc(adminUserId, Limit.of(10)))
				.singleElement()
				.satisfies(log -> assertThat(log.getTargetId()).isNull());
	}

	/**
	 * <b>기록 실패가 호출자를 막지 않는다.</b>
	 *
	 * <p>감사를 붙인 대가로 관리자 기능이 멈추면 감사를 떼게 된다 (B-25 와 같은 판단).
	 * {@code adminUserId} 가 없는 호출은 엔티티가 거부하지만 그 예외가 밖으로 나가지 않는다.
	 */
	@Test
	void R14_5_a_failed_record_never_breaks_the_caller() {
		org.assertj.core.api.Assertions
				.assertThatCode(() -> this.recorder.record(null, "admin.debug.read", "session", null,
						Map.of(), "ip-hash"))
				.doesNotThrowAnyException();
		assertThat(this.logs.count()).isZero();
	}

	/** <b>append-only 다.</b> 엔티티에 세터가 없고 리포지터리에 갱신 경로가 없다. */
	@Test
	void R14_5_the_audit_log_has_no_update_path() {
		assertThat(AdminAuditLog.class.getMethods())
				.extracting(java.lang.reflect.Method::getName)
				.filteredOn(name -> name.startsWith("set"))
				.isEmpty();
	}
}

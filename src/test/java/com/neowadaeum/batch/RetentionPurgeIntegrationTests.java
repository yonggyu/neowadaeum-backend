package com.neowadaeum.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.ai.log.AccessAuditLogRepository;
import com.neowadaeum.ai.log.AdminAuditLogRepository;
import com.neowadaeum.common.spi.LogRetentionPurge;
import com.neowadaeum.common.spi.SessionExpiry;
import com.neowadaeum.common.support.RetentionProperties;
import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.domain.SessionStatus;
import com.neowadaeum.play.repository.PlaySessionRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * B-61(1/2) — <b>지운다고 적었으면 실제로 지워져야 한다</b> (R12.4, S-10, §4.7).
 *
 * <p>S-10 이 <b>"파기 배치를 실제로 구현하고 테스트한다"</b> 를 명시한 이유가 이것이다. 지운다는
 * 코드가 있는 것과 <b>지워진 것</b>은 다르며, 확인하지 않으면 약관이 거짓이 된다.
 *
 * <p><b>남아야 할 것도 함께 센다.</b> "전부 지우는 구현"도 지운 것만 확인하면 통과한다.
 */
class RetentionPurgeIntegrationTests extends ContainerTestBase {

	private static final UUID STORY_ID = UUID.fromString("11111111-1111-4111-8111-000000000001");

	private static final UUID VERSION_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

	@Autowired
	private LogRetentionPurge logPurge;

	@Autowired
	private SessionExpiry sessionExpiry;

	@Autowired
	private RetentionProperties retention;

	@Autowired
	private AdminAuditLogRepository adminAuditLogs;

	@Autowired
	private AccessAuditLogRepository accessAuditLogs;

	@Autowired
	private PlaySessionRepository sessions;

	@Autowired
	@Qualifier("promptLogDataSource")
	private DataSource promptLog;

	@Autowired
	@Qualifier("playDataSource")
	private DataSource play;

	private final java.util.List<UUID> createdSessions = new java.util.ArrayList<>();

	/**
	 * <b>내가 만든 세션만 지운다.</b>
	 *
	 * <p>컨테이너는 한 벌이고 테스트 클래스가 나눠 쓴다 — {@code deleteAll()} 은 남의 세션까지
	 * 지우고, 그 세션에 턴이 매달려 있으면 제약에 걸린다.
	 */
	@AfterEach
	void clear() {
		this.adminAuditLogs.deleteAll();
		this.accessAuditLogs.deleteAll();
		this.createdSessions.forEach(this.sessions::deleteById);
		this.createdSessions.clear();
	}

	/** <b>보관 기간이 지난 감사 로그는 사라진다</b> (S-10 — 3년). */
	@Test
	void SEC10_an_audit_log_past_its_retention_is_gone() {
		UUID adminUserId = UUID.randomUUID();
		givenAdminAuditLog(adminUserId, yearsAgo(this.retention.auditLogYears() + 1));

		this.logPurge.purgeExpiredLogs();

		assertThat(this.adminAuditLogs.findAll()).noneSatisfy(
				log -> assertThat(log.getAdminUserId()).isEqualTo(adminUserId));
	}

	/**
	 * <b>기간이 지나지 않은 것은 남는다.</b>
	 *
	 * <p>이것이 없으면 "전부 지우는 구현"도 통과한다 — 그리고 그 구현은 <b>사후 추적의 근거를
	 * 통째로 지운다.</b>
	 */
	@Test
	void SEC10_an_audit_log_within_its_retention_stays() {
		UUID adminUserId = UUID.randomUUID();
		givenAdminAuditLog(adminUserId, yearsAgo(this.retention.auditLogYears() - 1));

		this.logPurge.purgeExpiredLogs();

		assertThat(this.adminAuditLogs.findAll()).anySatisfy(
				log -> assertThat(log.getAdminUserId()).isEqualTo(adminUserId));
	}

	/** 열람 감사도 같은 기간을 지킨다 (R12.3, S-5). */
	@Test
	void SEC10_an_access_audit_log_past_its_retention_is_gone() {
		UUID resourceId = UUID.randomUUID();
		givenAccessAuditLog(resourceId, yearsAgo(this.retention.auditLogYears() + 1));

		this.logPurge.purgeExpiredLogs();

		assertThat(this.accessAuditLogs.findAll()).noneSatisfy(
				log -> assertThat(log.getResourceId()).isEqualTo(resourceId));
	}

	/**
	 * <b>무활동 세션은 만료가 된다</b> (§4.7).
	 *
	 * <p>지워지지 않는다 — 기록은 남고 이어갈 수만 없게 된다.
	 */
	@Test
	void S4_7_an_idle_session_becomes_expired() {
		UUID sessionId = givenSession(daysAgo(this.retention.sessionIdleDays() + 1));

		int expired = this.sessionExpiry.expireIdleSessions();

		assertThat(expired).isEqualTo(1);
		assertThat(this.sessions.findById(sessionId)).get()
				.satisfies(session -> {
					assertThat(session.getStatus()).isEqualTo(SessionStatus.EXPIRED);
					assertThat(session.getExpiresAt()).isNotNull();
				});
	}

	/** 최근에 손댄 세션은 그대로다 — 만료가 진행 중인 플레이를 끊으면 안 된다. */
	@Test
	void S4_7_a_recently_touched_session_is_left_alone() {
		UUID sessionId = givenSession(daysAgo(this.retention.sessionIdleDays() - 1));

		this.sessionExpiry.expireIdleSessions();

		assertThat(this.sessions.findById(sessionId)).get()
				.satisfies(session -> assertThat(session.getStatus()).isEqualTo(SessionStatus.ACTIVE));
	}

	/**
	 * <b>지운 세션은 건드리지 않는다.</b>
	 *
	 * <p>사용자가 이미 지운 것을 배치가 만료로 바꾸면, 지운 이유와 만료된 이유가 한 행에서 섞인다.
	 */
	@Test
	void S4_7_a_deleted_session_is_not_expired() {
		UUID sessionId = givenSession(daysAgo(this.retention.sessionIdleDays() + 1));
		// **SQL 로 표시한다.** 도메인의 삭제는 상태를 abandoned 로 바꾸고 updated_at 을 지금으로
		// 옮기므로, 그것을 쓰면 이 테스트는 "지워졌기 때문"이 아니라 "최근이기 때문"에 통과한다.
		markDeleted(sessionId);

		this.sessionExpiry.expireIdleSessions();

		assertThat(this.sessions.findById(sessionId)).get()
				.satisfies(session -> assertThat(session.getStatus()).isEqualTo(SessionStatus.ACTIVE));
	}

	/** 파기는 <b>몇 건인지</b>를 돌려준다 — batch 가 그것만 로그에 남긴다 (§9.4, S-11). */
	@Test
	void SEC10_the_purge_reports_how_many_rows_it_removed() {
		givenAdminAuditLog(UUID.randomUUID(), yearsAgo(this.retention.auditLogYears() + 1));
		givenAdminAuditLog(UUID.randomUUID(), yearsAgo(this.retention.auditLogYears() + 1));

		assertThat(this.logPurge.purgeExpiredLogs()).isGreaterThanOrEqualTo(2);
	}

	/**
	 * <b>행을 SQL 로 넣는다.</b>
	 *
	 * <p>기록 경로는 <b>지금 시각</b>을 찍으므로 (그것이 감사 로그의 요건이다) 오래된 행을
	 * 만들 수 없다. 여기서 확인하려는 것은 <b>기간이 지난 행이 지워지는가</b>이지 어떻게
	 * 기록되는가가 아니다.
	 */
	private void givenAdminAuditLog(UUID adminUserId, Instant at) {
		JdbcClient.create(this.promptLog).sql("""
						INSERT INTO admin_audit_log (id, admin_user_id, action, target_type, target_id,
								payload, created_at)
						VALUES (?, ?, 'test.action', 'story', ?, '{}'::jsonb, ?)
						""")
				.params(UUID.randomUUID(), adminUserId, UUID.randomUUID(), at.atOffset(ZoneOffset.UTC))
				.update();
	}

	private void givenAccessAuditLog(UUID resourceId, Instant at) {
		JdbcClient.create(this.promptLog).sql("""
						INSERT INTO access_audit_log (id, admin_user_id, resource, resource_id, created_at)
						VALUES (?, ?, 'story_draft', ?, ?)
						""")
				.params(UUID.randomUUID(), UUID.randomUUID(), resourceId,
						at.atOffset(ZoneOffset.UTC))
				.update();
	}

	private void markDeleted(UUID sessionId) {
		JdbcClient.create(this.play).sql("UPDATE play_session SET deleted_at = NOW() WHERE id = ?")
				.param(sessionId).update();
	}

	private UUID givenSession(Instant lastTouched) {
		PlaySession session = this.sessions.saveAndFlush(PlaySession.start(UUID.randomUUID(),
				STORY_ID, VERSION_ID, "fixed", "scenario", false, lastTouched));
		this.createdSessions.add(session.getId());
		return session.getId();
	}

	private static Instant daysAgo(int days) {
		return Instant.now().minus(days, ChronoUnit.DAYS);
	}

	private static Instant yearsAgo(int years) {
		return Instant.now().minus(365L * years, ChronoUnit.DAYS);
	}
}

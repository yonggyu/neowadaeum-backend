package com.neowadaeum.play.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.neowadaeum.ContainerTestBase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * S-2 (#39) — 규칙이 <b>DB 제약으로</b> 성립하는지 확인한다.
 *
 * <p>애플리케이션이 먼저 확인하더라도 동시 요청 두 개는 그 확인을 나란히 통과한다. 마지막 방어선은
 * DB 여야 하고, 그것이 실제로 거절하는지는 DB 에 대고 봐야 안다. 그래서 이 테스트는 엔티티를 쓰지 않고
 * SQL 로 직접 찌른다 — 엔티티를 거치면 <b>엔티티가 막았는지 DB 가 막았는지</b> 구분되지 않는다.
 *
 * <p>문구 대신 SQLState 로 본다. 메시지는 PostgreSQL 버전과 로케일에 따라 바뀐다.
 */
class SessionConstraintTests extends ContainerTestBase {

	/** PostgreSQL {@code unique_violation}. */
	private static final String UNIQUE_VIOLATION = "23505";

	/** PostgreSQL {@code check_violation}. */
	private static final String CHECK_VIOLATION = "23514";

	private static final Instant NOW = Instant.parse("2026-08-23T04:05:06Z");

	@Autowired
	@Qualifier("playDataSource")
	private DataSource dataSource;

	/**
	 * §13-9 — 작품당 active 세션은 1개다.
	 *
	 * <p>같은 사람이 같은 작품을 두 번 열 수 없다는 뜻이지, 다시 시작할 수 없다는 뜻이 아니다.
	 * {@code restart=true} 는 기존 active 를 {@code abandoned} 로 바꾼 뒤 새로 만든다 — 그 경로가
     * 막히지 않는다는 것까지 함께 확인한다.
	 */
	@Test
	void S13_9_only_one_active_session_is_allowed_per_story() throws SQLException {
		UUID playerRef = UUID.randomUUID();
		UUID storyId = UUID.randomUUID();
		insertSession(playerRef, storyId, "active");

		assertRejected(UNIQUE_VIOLATION, () -> insertSession(playerRef, storyId, "active"));
	}

	/** §13-9 — 버려진 세션은 유일성 대상이 아니다. restart 가 막히면 안 된다. */
	@Test
	void S13_9_abandoned_session_does_not_block_a_new_one() throws SQLException {
		UUID playerRef = UUID.randomUUID();
		UUID storyId = UUID.randomUUID();
		insertSession(playerRef, storyId, "abandoned");

		UUID restarted = insertSession(playerRef, storyId, "active");

		assertThat(restarted).isNotNull();
	}

	/**
	 * §13-6 — 상태값은 넷뿐이다.
	 *
	 * <p>{@code in_progress} 는 API 쿼리 파라미터이지 상태가 아니다. 저장이 통과하면 그 구분이 사라지고,
	 * 조회는 조용히 0건을 돌려준다.
	 */
	@Test
	void S13_6_in_progress_is_not_a_session_status() {
		assertRejected(CHECK_VIOLATION,
				() -> insertSession(UUID.randomUUID(), UUID.randomUUID(), "in_progress"));
	}

	/** I-6 — 한 세션에 같은 번호의 턴이 둘일 수 없다. 낙관적 잠금이 성립하는 근거다. */
	@Test
	void I6_turn_number_is_unique_within_a_session() throws SQLException {
		UUID sessionId = insertSession(UUID.randomUUID(), UUID.randomUUID(), "active");
		insertTurn(sessionId, 1);

		assertRejected(UNIQUE_VIOLATION, () -> insertTurn(sessionId, 1));
	}

	/** I-5 — 스냅샷은 턴당 1행이다. 두 번째 행이 들어가면 어느 것이 그 턴의 상태인지 알 수 없다. */
	@Test
	void I5_snapshot_is_one_live_row_per_turn() throws SQLException {
		UUID sessionId = insertSession(UUID.randomUUID(), UUID.randomUUID(), "active");
		insertSnapshot(sessionId, 1);

		assertRejected(UNIQUE_VIOLATION, () -> insertSnapshot(sessionId, 1));
	}

	/**
	 * §13-9 — 되돌린 스냅샷은 자리를 비켜 준다.
	 *
	 * <p>유일성이 <b>살아 있는 행 기준</b>이라 롤백 후 재생성(B-42)이 같은 턴 번호로 새 행을 남길 수 있다.
	 * 전체 기준으로 잠갔다면 여기서 막히고, 결국 UPDATE 로 되돌아가 I-5 가 깨진다.
	 */
	@Test
	void S13_9_soft_deleted_snapshot_frees_the_turn_for_a_new_row() throws SQLException {
		UUID sessionId = insertSession(UUID.randomUUID(), UUID.randomUUID(), "active");
		UUID first = insertSnapshot(sessionId, 1);
		softDeleteSnapshot(first);

		UUID second = insertSnapshot(sessionId, 1);

		assertThat(second).isNotEqualTo(first);
	}

	private UUID insertSession(UUID playerRef, UUID storyId, String status) throws SQLException {
		UUID id = UUID.randomUUID();
		try (Connection connection = this.dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO play_session (id, player_ref, story_id, story_version_id, provider_id,
								model_id, status, turn_no, chapter_no, started_at, last_played_at, completed_at)
						VALUES (?, ?, ?, ?, 'fixed', 'scenario-v1', ?, 0, 1, ?, ?, ?)
						""")) {
			statement.setObject(1, id);
			statement.setObject(2, playerRef);
			statement.setObject(3, storyId);
			statement.setObject(4, UUID.randomUUID());
			statement.setString(5, status);
			statement.setTimestamp(6, Timestamp.from(NOW));
			statement.setTimestamp(7, Timestamp.from(NOW));
			statement.setTimestamp(8, "completed".equals(status) ? Timestamp.from(NOW) : null);
			statement.executeUpdate();
		}
		return id;
	}

	private UUID insertTurn(UUID sessionId, int turnNo) throws SQLException {
		UUID id = UUID.randomUUID();
		try (Connection connection = this.dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO turn (id, session_id, turn_no, chapter_no, paragraphs, choices, created_at)
						VALUES (?, ?, ?, 1, '[]'::jsonb, '[]'::jsonb, ?)
						""")) {
			statement.setObject(1, id);
			statement.setObject(2, sessionId);
			statement.setInt(3, turnNo);
			statement.setTimestamp(4, Timestamp.from(NOW));
			statement.executeUpdate();
		}
		return id;
	}

	private UUID insertSnapshot(UUID sessionId, int turnNo) throws SQLException {
		UUID id = UUID.randomUUID();
		try (Connection connection = this.dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO game_state_snapshot (id, session_id, turn_no, state, created_at)
						VALUES (?, ?, ?, '{}'::jsonb, ?)
						""")) {
			statement.setObject(1, id);
			statement.setObject(2, sessionId);
			statement.setInt(3, turnNo);
			statement.setTimestamp(4, Timestamp.from(NOW));
			statement.executeUpdate();
		}
		return id;
	}

	private void softDeleteSnapshot(UUID id) throws SQLException {
		try (Connection connection = this.dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"UPDATE game_state_snapshot SET deleted_at = ? WHERE id = ?")) {
			statement.setTimestamp(1, Timestamp.from(NOW));
			statement.setObject(2, id);
			statement.executeUpdate();
		}
	}

	private static void assertRejected(String expectedSqlState, Insert insert) {
		try {
			insert.run();
			fail("제약이 이 삽입을 거절하지 않았다. SQLState %s 를 기대했다.", expectedSqlState);
		}
		catch (SQLException ex) {
			assertThat(ex.getSQLState())
					.as("거절 사유가 기대와 다르다 (실제: %s)", ex.getSQLState())
					.isEqualTo(expectedSqlState);
		}
	}

	@FunctionalInterface
	private interface Insert {

		void run() throws SQLException;

	}
}

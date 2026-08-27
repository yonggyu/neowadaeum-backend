package com.neowadaeum.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.ContainerTestBase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * B-10 — Authoring 스키마의 <b>제약이 실제로 막는가</b> (§2.4).
 *
 * <p>여기서 확인하는 것은 컬럼이 있는지가 아니라 <b>어떤 규칙이 제약으로 표현되었는가</b>다.
 * 애플리케이션이 지키기로 한 규칙과 DB 가 강제하는 규칙이 갈라지면, 갈라진 그 순간이 아니라
 * <b>한참 뒤에</b> 드러난다.
 *
 * <p><b>S-11 — 픽스처는 전부 가상의 문자열이다.</b> 실제 블록리스트 항목을 공개 레포에 적지 않는다.
 */
class AuthoringSchemaConstraintTests extends ContainerTestBase {

	/** PostgreSQL {@code unique_violation}. */
	private static final String UNIQUE_VIOLATION = "23505";

	/** PostgreSQL {@code check_violation}. */
	private static final String CHECK_VIOLATION = "23514";

	@Autowired
	@Qualifier("catalogDataSource")
	private DataSource dataSource;

	/** R8.3 — 5단계 작성이다. 범위 밖 단계는 들어가지 못한다. */
	@Test
	void R8_3_a_draft_step_stays_within_five() throws SQLException {
		insertDraft(3, "clean");

		assertRejected(CHECK_VIOLATION, () -> insertDraft(6, "clean"));
		assertRejected(CHECK_VIOLATION, () -> insertDraft(0, "clean"));
	}

	/** L0 결과는 세 값뿐이다 (B-50). 오타 하나가 조용히 통과하면 안 된다. */
	@Test
	void R8_3_a_draft_safety_state_is_one_of_three() throws SQLException {
		insertDraft(1, "blocked");

		assertRejected(CHECK_VIOLATION, () -> insertDraft(1, "ok"));
	}

	/**
	 * <b>인간 검수에는 검수자가 있어야 한다</b> (R8.6).
	 *
	 * <p>누가 승인했는지 모르는 승인은 감사에 쓸모가 없다. 자동 검수에는 사람이 없으므로
	 * 그쪽은 비어 있어도 된다.
	 */
	@Test
	void R8_6_a_human_review_carries_its_reviewer() throws SQLException {
		insertReview("auto", "pass", null);
		insertReview("human", "pass", UUID.randomUUID());

		assertRejected(CHECK_VIOLATION, () -> insertReview("human", "pass", null));
	}

	/** 판정은 세 값뿐이다 (R8.7). */
	@Test
	void R8_7_a_review_verdict_is_one_of_three() throws SQLException {
		insertReview("auto", "hold", null);

		assertRejected(CHECK_VIOLATION, () -> insertReview("auto", "approved", null));
	}

	/**
	 * <b>같은 사람이 같은 대상을 두 번 신고해도 한 건이다</b> (R8.9).
	 *
	 * <p>누적 3건이 자동 정지의 근거다. 중복이 세어지면 <b>한 사람이 혼자 작품을 내릴 수 있다.</b>
	 */
	@Test
	void R8_9_a_reporter_counts_once_per_target() throws SQLException {
		UUID reporter = UUID.randomUUID();
		UUID target = UUID.randomUUID();
		insertReport(reporter, target, "inappropriate");

		assertRejected(UNIQUE_VIOLATION, () -> insertReport(reporter, target, "other"));
	}

	/** 다른 사람의 신고는 별개다 — 그러지 않으면 누적이라는 말이 성립하지 않는다. */
	@Test
	void R8_9_another_reporter_is_a_separate_report() throws SQLException {
		UUID target = UUID.randomUUID();
		insertReport(UUID.randomUUID(), target, "inappropriate");

		insertReport(UUID.randomUUID(), target, "inappropriate");

		assertThat(countReports(target)).isEqualTo(2);
	}

	/**
	 * <b>정규화 값은 유일하다</b> (R2.5).
	 *
	 * <p>같은 값이 둘이면 대조가 두 번 일어나고, 지울 때 하나만 지워진다 — 지웠는데 여전히
	 * 걸리는 상태가 된다.
	 */
	@Test
	void R2_5_a_normalized_value_exists_once() throws SQLException {
		String normalized = "가상항목" + UUID.randomUUID().toString().substring(0, 8);
		insertBlocklist("phrase", normalized, "block");

		assertRejected(UNIQUE_VIOLATION, () -> insertBlocklist("real_person", normalized, "warn"));
	}

	/** 종류와 심각도는 목록에 있는 값뿐이다. */
	@Test
	void R2_5_a_blocklist_entry_uses_known_kinds_and_severities() throws SQLException {
		insertBlocklist("ip_title", unique(), "warn");

		assertRejected(CHECK_VIOLATION, () -> insertBlocklist("unknown_kind", unique(), "block"));
		assertRejected(CHECK_VIOLATION, () -> insertBlocklist("phrase", unique(), "maybe"));
	}

	private static String unique() {
		return "가상값" + UUID.randomUUID().toString().substring(0, 8);
	}

	private void insertDraft(int step, String safetyState) throws SQLException {
		execute("INSERT INTO story_draft (id, author_ref, step, safety_state) VALUES (?, ?, ?, ?)",
				statement -> {
					statement.setObject(1, UUID.randomUUID());
					statement.setObject(2, UUID.randomUUID());
					statement.setInt(3, step);
					statement.setString(4, safetyState);
				});
	}

	private void insertReview(String stage, String verdict, UUID reviewerRef) throws SQLException {
		execute("INSERT INTO story_review (id, story_id, stage, verdict, reviewer_ref) "
				+ "VALUES (?, ?, ?, ?, ?)", statement -> {
					statement.setObject(1, UUID.randomUUID());
					statement.setObject(2, UUID.randomUUID());
					statement.setString(3, stage);
					statement.setString(4, verdict);
					statement.setObject(5, reviewerRef);
				});
	}

	private void insertReport(UUID reporterRef, UUID targetId, String reason) throws SQLException {
		execute("INSERT INTO content_report (id, reporter_ref, target_type, target_id, reason) "
				+ "VALUES (?, ?, 'story', ?, ?)", statement -> {
					statement.setObject(1, UUID.randomUUID());
					statement.setObject(2, reporterRef);
					statement.setObject(3, targetId);
					statement.setString(4, reason);
				});
	}

	private void insertBlocklist(String kind, String normalized, String severity) throws SQLException {
		execute("INSERT INTO blocklist_entry (id, kind, value, normalized_value, severity) "
				+ "VALUES (?, ?, ?, ?, ?)", statement -> {
					statement.setObject(1, UUID.randomUUID());
					statement.setString(2, kind);
					statement.setString(3, normalized);
					statement.setString(4, normalized);
					statement.setString(5, severity);
				});
	}

	private int countReports(UUID targetId) throws SQLException {
		try (Connection connection = this.dataSource.getConnection();
				PreparedStatement statement = connection
						.prepareStatement("SELECT COUNT(*) FROM content_report WHERE target_id = ?")) {
			statement.setObject(1, targetId);
			try (var rows = statement.executeQuery()) {
				rows.next();
				return rows.getInt(1);
			}
		}
	}

	private void execute(String sql, StatementBinder binder) throws SQLException {
		try (Connection connection = this.dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(sql)) {
			binder.bind(statement);
			statement.executeUpdate();
		}
	}

	/** 제약이 <b>어떤 이유로</b> 막았는지까지 본다 — 아무 예외나 나면 통과하는 테스트는 약하다. */
	private static void assertRejected(String sqlState, ThrowingCall call) {
		assertThatThrownBy(call::run)
				.isInstanceOf(SQLException.class)
				.extracting(ex -> ((SQLException) ex).getSQLState())
				.isEqualTo(sqlState);
	}

	@FunctionalInterface
	private interface StatementBinder {

		void bind(PreparedStatement statement) throws SQLException;
	}

	@FunctionalInterface
	private interface ThrowingCall {

		void run() throws SQLException;
	}
}

package com.neowadaeum.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.neowadaeum.ContainerTestBase;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-4 (#48) — 시드가 들어갔는지, 그리고 규칙이 <b>DB 제약으로</b> 성립하는지 확인한다.
 *
 * <p>엔티티를 거치지 않고 SQL 로 직접 찌른다. 엔티티를 거치면 <b>엔티티가 막았는지 DB 가 막았는지</b>
 * 구분되지 않는다 (S-2 의 {@code SessionConstraintTests} 와 같은 이유). catalog 모듈에는 아직 엔티티가
 * 없기도 하다 — B-08 은 슬라이스에서 제외돼 있다.
 *
 * <p>문구 대신 SQLState 로 본다. 메시지는 PostgreSQL 버전과 로케일에 따라 바뀐다.
 */
class CatalogSeedTests extends ContainerTestBase {

	/** PostgreSQL {@code unique_violation}. */
	private static final String UNIQUE_VIOLATION = "23505";

	/** PostgreSQL {@code check_violation}. */
	private static final String CHECK_VIOLATION = "23514";

	private static final UUID SEED_STORY = UUID.fromString("11111111-1111-4111-8111-000000000001");
	private static final UUID SEED_VERSION = UUID.fromString("11111111-1111-4111-8111-111111111111");

	@Autowired
	@Qualifier("catalogDataSource")
	private DataSource dataSource;

	/** S-4 — 작품 1 / 챕터 3 / 엔딩 2 / 캐릭터 1. B-45 의 축소판이다. */
	@Test
	void S4_seed_provides_one_story_three_chapters_two_endings_one_character() throws SQLException {
		assertThat(count("story")).isEqualTo(1);
		assertThat(count("story_version")).isEqualTo(1);
		assertThat(count("chapter_def")).isEqualTo(3);
		assertThat(count("ending_def")).isEqualTo(2);
		assertThat(count("character")).isEqualTo(1);
	}

	/**
	 * §13-1 — 챕터·엔딩·캐릭터는 <b>작품 버전</b>에 묶인다.
	 *
	 * <p>{@code story_id} 로 묶으면 작성자가 엔딩 조건을 고칠 때 진행 중인 모든 세션이 즉시 영향을
	 * 받는다. 버전 고정이 {@code world_prompt} 하나에만 걸리는 상태가 그것이다.
	 *
	 * <p>컬럼이 있는지가 아니라 <b>FK 가 실제로 story_version 을 가리키는지</b>를 본다. 컬럼만 있고
	 * 참조가 story 로 가 있으면 정정을 반영하지 않은 것과 같다.
	 */
	@Test
	void S13_1_version_scoped_tables_reference_story_version_not_story() throws SQLException {
		for (String table : List.of("chapter_def", "ending_def", "character")) {
			assertThat(foreignKeyTargets(table))
					.as("%s 의 FK 대상 (§13-1)", table)
					.containsExactly("story_version");
		}
	}

	/** §13-1 — {@code story_id} 는 조회 편의용 비정규화 컬럼으로 남아 있어야 한다. */
	@Test
	void S13_1_story_id_remains_as_a_denormalized_column() throws SQLException {
		for (String table : List.of("chapter_def", "ending_def", "character")) {
			assertThat(columns(table)).as("%s 컬럼", table).contains("story_id", "story_version_id");
		}
	}

	/**
	 * I-19 — {@code story.age_rating} 컬럼을 만들지 않는다.
	 *
	 * <p>컬럼이 생기는 순간 작품마다 다른 값이 들어갈 수 있게 되고, 그때부터 단일 등급은 상수가 아니라
	 * 데이터가 된다. 단일 상수 응답이라는 결정이 스키마에서 지켜져야 한다.
	 */
	@Test
	void I19_story_has_no_age_rating_column() throws SQLException {
		assertThat(columns("story")).doesNotContain("age_rating");
	}

	/** §4.2 · §4.7 — {@code current_version_id} 는 실재하는 버전을 가리킨다. 순환 FK 대신 이 확인을 둔다. */
	@Test
	void S4_2_current_version_id_points_at_an_existing_version() throws SQLException {
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("""
						SELECT s.current_version_id, v.id
						FROM story s LEFT JOIN story_version v ON v.id = s.current_version_id
						""")) {
			assertThat(rows.next()).isTrue();
			assertThat(rows.getObject("current_version_id")).isNotNull();
			assertThat(rows.getObject(2)).as("current_version_id 가 실재하지 않는 버전을 가리킨다").isNotNull();
		}
	}

	/**
	 * §4.6 — 기본 엔딩은 순회에서 마지막이어야 한다.
	 *
	 * <p>{@code ending_no} 오름차순 최초 매칭이므로, 조건 없는 기본 엔딩이 앞에 오면 뒤의 조건부 엔딩은
	 * <b>영원히 도달되지 않는다.</b> 제약으로 표현할 수 없는 성질이라 시드에 대고 확인한다.
	 */
	@Test
	void S4_6_default_ending_is_last_in_ending_no_order() throws SQLException {
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery(
						"SELECT is_default FROM ending_def ORDER BY ending_no")) {
			List<Boolean> flags = new ArrayList<>();
			while (rows.next()) {
				flags.add(rows.getBoolean(1));
			}
			assertThat(flags).containsExactly(false, true);
		}
	}

	/**
	 * S-3 와 S-4 가 같은 작품을 가리킨다.
	 *
	 * <p>시드의 {@code story_version.id} 와 {@code FixedStoryProvider} 데모 시나리오의
	 * {@code storyVersionRef} 가 어긋나면 S-9 에서 조용히 "시나리오에 없는 요청"이 된다. 증상은
	 * 파이프라인 실패로 보이고 원인이 UUID 라는 것을 찾는 데 시간이 든다.
	 */
	@Test
	void S4_seed_version_matches_the_fixed_story_scenario_reference() throws Exception {
		JsonNode scenario;
		try (InputStream in = new ClassPathResource("scenarios/demo-first-day.json").getInputStream()) {
			scenario = JsonMapper.builder().build().readTree(in);
		}

		assertThat(UUID.fromString(scenario.get("storyVersionRef").asString()))
				.as("데모 시나리오와 시드가 같은 작품 버전을 가리켜야 한다")
				.isEqualTo(SEED_VERSION);
		assertThat(scalar("SELECT id FROM story_version")).isEqualTo(SEED_VERSION);
	}

	// ── 제약 ─────────────────────────────────────────────────

	/** 기본 엔딩은 작품 버전당 정확히 1개다. 2개면 어느 쪽으로 끝나는지가 행 순서에 달린다. */
	@Test
	void S3_1_a_second_default_ending_is_rejected() {
		assertRejected(UNIQUE_VIOLATION, () -> insertEnding(99, null, true, false));
	}

	/** 폴백이 총계에서 감춰지면 도달률 표기가 어긋난다 (R7.11). */
	@Test
	void S3_1_default_ending_cannot_be_secret() {
		assertRejected(CHECK_VIOLATION, () -> insertEnding(98, null, true, true));
	}

	/** 조건 없는 일반 엔딩은 순회에서 항상 최초 매칭이 되어 뒤를 전부 가린다 (§4.6). */
	@Test
	void S4_6_non_default_ending_requires_a_condition() {
		assertRejected(CHECK_VIOLATION, () -> insertEnding(97, null, false, false));
	}

	/** 기본 엔딩이 조건을 가지면 폴백이 아니다 (§3.1). */
	@Test
	void S3_1_default_ending_must_not_carry_a_condition() {
		assertRejected(CHECK_VIOLATION, () -> insertEnding(96, "{\"gte\": [\"affinity.yuna\", 1]}", true, false));
	}

	/** §4.5 — min_turns 충족 후 조건 평가, 불만족이면 max_turns 에서 강제 전환. max < min 이면 성립하지 않는다. */
	@Test
	void S4_5_chapter_max_turns_must_not_be_below_min_turns() {
		assertRejected(CHECK_VIOLATION, () -> insertChapter(99, 5, 2));
	}

	/** 챕터 번호는 작품 버전 안에서 유일하다. 중복되면 전환 판정이 어느 행을 볼지 정해지지 않는다. */
	@Test
	void S4_5_chapter_number_is_unique_within_a_version() {
		assertRejected(UNIQUE_VIOLATION, () -> insertChapter(1, 1, 3));
	}

	// ── helpers ──────────────────────────────────────────────

	private void insertEnding(int endingNo, String condition, boolean isDefault, boolean isSecret)
			throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO ending_def (id, story_version_id, story_id, ending_no, label,
						                        condition, epilogue, is_default, is_secret)
						VALUES (?, ?, ?, ?, '검증용', CAST(? AS JSONB), '에필로그', ?, ?)
						""")) {
			statement.setObject(1, UUID.randomUUID());
			statement.setObject(2, SEED_VERSION);
			statement.setObject(3, SEED_STORY);
			statement.setInt(4, endingNo);
			statement.setString(5, condition);
			statement.setBoolean(6, isDefault);
			statement.setBoolean(7, isSecret);
			statement.executeUpdate();
		}
	}

	private void insertChapter(int chapterNo, int minTurns, int maxTurns) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO chapter_def (id, story_version_id, story_id, chapter_no, title,
						                         entry_condition, min_turns, max_turns)
						VALUES (?, ?, ?, ?, '검증용', NULL, ?, ?)
						""")) {
			statement.setObject(1, UUID.randomUUID());
			statement.setObject(2, SEED_VERSION);
			statement.setObject(3, SEED_STORY);
			statement.setInt(4, chapterNo);
			statement.setInt(5, minTurns);
			statement.setInt(6, maxTurns);
			statement.executeUpdate();
		}
	}

	private int count(String table) throws SQLException {
		return ((Number) scalarObject("SELECT count(*) FROM " + table)).intValue();
	}

	private UUID scalar(String sql) throws SQLException {
		return (UUID) scalarObject(sql);
	}

	private Object scalarObject(String sql) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery(sql)) {
			assertThat(rows.next()).as("행이 없다: %s", sql).isTrue();
			return rows.getObject(1);
		}
	}

	private List<String> columns(String table) throws SQLException {
		return queryStrings("""
				SELECT column_name FROM information_schema.columns
				WHERE table_schema = current_schema() AND table_name = '%s'
				""".formatted(table));
	}

	private List<String> foreignKeyTargets(String table) throws SQLException {
		return queryStrings("""
				SELECT DISTINCT rt.relname
				FROM pg_constraint c
				JOIN pg_class t  ON t.oid  = c.conrelid
				JOIN pg_class rt ON rt.oid = c.confrelid
				WHERE c.contype = 'f' AND t.relname = '%s'
				""".formatted(table));
	}

	private List<String> queryStrings(String sql) throws SQLException {
		List<String> values = new ArrayList<>();
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery(sql)) {
			while (rows.next()) {
				values.add(rows.getString(1));
			}
		}
		return values;
	}

	private void assertRejected(String expectedSqlState, ThrowingInsert insert) {
		try {
			insert.run();
			fail("DB 가 거절해야 하는 INSERT 가 통과했다 (기대 SQLState %s)", expectedSqlState);
		}
		catch (SQLException ex) {
			assertThat(ex.getSQLState()).as("실제 오류: %s", ex.getMessage()).isEqualTo(expectedSqlState);
		}
	}

	@FunctionalInterface
	private interface ThrowingInsert {

		void run() throws SQLException;
	}
}

package com.neowadaeum.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.neowadaeum.TestcontainersConfiguration;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

/**
 * §5.3 스토어 물리 분리를 실제 DB 에 대고 확인한다. B-05 완료 조건 1 · 2 · 4.
 *
 * <p>여기서 확인하는 성질은 전부 "지금은 당연히 참"이다. 도메인 테이블이 아직 없기 때문이다.
 * 그래서 이 테스트의 값은 오늘이 아니라 B-07 이후에 있다. 스키마 간 FK 나 크로스 스키마 접근이
 * 처음 들어오는 커밋에서 빨갛게 터지는 것이 목적이다.
 *
 * <p>초기에는 동일 인스턴스 내 스키마 분리로 시작하고 트래픽·규제 요구에 따라 인스턴스 분리로 승격한다.
 * <b>승격 시 애플리케이션 코드는 변경이 없어야 한다</b> — 그것이 이 규칙들의 존재 이유다.
 *
 * <p><b>이 테스트 자체는 동일 인스턴스를 전제한다.</b> 커넥션 하나로 {@code pg_catalog} 를 읽어 네 스키마를
 * 한 번에 관찰하기 때문이다. §5.3 의 인스턴스 분리 승격 시에는 스토어별 조회로 나눠야 한다 —
 * 애플리케이션 코드가 아니라 이 검증 방식이 바뀐다.
 */
@Tag("container")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class StoreSeparationTests {

	/** PostgreSQL {@code insufficient_privilege}. 메시지 문구 대신 SQLState 로 본다. */
	private static final String INSUFFICIENT_PRIVILEGE = "42501";

	@Autowired
	private ApplicationContext context;

	/** 완료 조건 1 — 스토어마다 자기 스키마에서 자기 마이그레이션이 실행됐다. */
	@Test
	void S5_3_each_store_migrates_its_own_schema() throws SQLException {
		for (StoreSchema store : StoreSchema.values()) {
			List<String> versions = new ArrayList<>();
			try (Connection connection = connection(store);
					Statement statement = connection.createStatement();
					ResultSet rows = statement.executeQuery(
							"SELECT version, success FROM flyway_schema_history ORDER BY installed_rank")) {
				while (rows.next()) {
					assertThat(rows.getBoolean("success"))
							.as("%s 스토어의 마이그레이션 %s 가 실패했다", store.schema(), rows.getString("version"))
							.isTrue();
					versions.add(rows.getString("version"));
				}
			}
			assertThat(versions).as("%s 스토어 마이그레이션 이력", store.schema()).containsExactly("1");
		}
	}

	/**
	 * §5.3 — 이력 테이블도 스토어별로 분리된다.
	 *
	 * <p>한 곳에 모으면 {@code flyway_schema_history} 자체가 크로스 스키마 참조점이 되어, 인스턴스
	 * 분리로 승격하는 순간 네 스토어가 서로를 필요로 하게 된다.
	 */
	@Test
	void S5_3_history_tables_live_in_four_separate_schemas() throws SQLException {
		List<String> schemas = new ArrayList<>();
		try (Connection connection = connection(StoreSchema.IDENTITY);
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("""
						SELECT n.nspname
						FROM pg_class c
						JOIN pg_namespace n ON n.oid = c.relnamespace
						WHERE c.relname = 'flyway_schema_history'
						  AND n.nspname IN (%s)
						""".formatted(storeSchemaList()))) {
			while (rows.next()) {
				schemas.add(rows.getString(1));
			}
		}

		assertThat(schemas).containsExactlyInAnyOrderElementsOf(
				Arrays.stream(StoreSchema.values()).map(StoreSchema::schema).toList());
	}

	/**
	 * 완료 조건 2 — 스키마 간 FK 0건 (§5.3).
	 *
	 * <p>참조는 애플리케이션 레벨에서만 한다. FK 가 하나라도 생기면 스토어를 별도 인스턴스로 승격할 수 없다.
	 */
	@Test
	void S5_3_no_cross_schema_foreign_keys() throws SQLException {
		List<String> violations = new ArrayList<>();
		try (Connection connection = connection(StoreSchema.IDENTITY);
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("""
						SELECT tn.nspname || '.' || t.relname || ' -> ' || rn.nspname || '.' || rt.relname
						FROM pg_constraint c
						JOIN pg_class t      ON t.oid  = c.conrelid
						JOIN pg_namespace tn ON tn.oid = t.relnamespace
						JOIN pg_class rt     ON rt.oid = c.confrelid
						JOIN pg_namespace rn ON rn.oid = rt.relnamespace
						WHERE c.contype = 'f'
						  AND tn.nspname IN (%s)
						  AND tn.nspname <> rn.nspname
						""".formatted(storeSchemaList()))) {
			while (rows.next()) {
				violations.add(rows.getString(1));
			}
		}

		assertThat(violations).as("스키마 간 FK 는 만들지 않는다 (§5.3)").isEmpty();
	}

	/**
	 * 완료 조건 4 — 각 계정은 자기 스키마에만 권한을 갖는다 (§5.3).
	 *
	 * <p>스키마 간 JOIN 을 쓴 코드는 로컬에서 곧바로 권한 오류로 터져야 한다. 운영에 가서 발견하는 것보다
	 * 낫다(§2.5). 이 테스트는 그 전제가 실제로 성립하는지 12개 조합 전부를 확인한다 — 초기화 스크립트가
	 * 계정만 만들고 권한을 좁히지 않았을 가능성이 있기 때문이다.
	 */
	@Test
	void S5_3_each_account_is_denied_access_to_other_store_schemas() throws SQLException {
		for (StoreSchema store : StoreSchema.values()) {
			for (StoreSchema other : StoreSchema.values()) {
				if (store == other) {
					continue;
				}
				assertDenied(store, other);
			}
		}
	}

	private void assertDenied(StoreSchema store, StoreSchema other) throws SQLException {
		String sql = "SELECT 1 FROM %s.flyway_schema_history".formatted(other.schema());
		try (Connection connection = connection(store); Statement statement = connection.createStatement()) {
			statement.executeQuery(sql);
			fail("%s_user 가 %s 스키마를 읽을 수 있다. 각 계정은 자기 스키마에만 권한을 가져야 한다 (§5.3)",
					store.schema(), other.schema());
		}
		catch (SQLException ex) {
			assertThat(ex.getSQLState())
					.as("%s_user → %s 스키마 접근은 권한 오류여야 한다 (실제: %s)", store.schema(), other.schema(),
							ex.getMessage())
					.isEqualTo(INSUFFICIENT_PRIVILEGE);
		}
	}

	private Connection connection(StoreSchema store) throws SQLException {
		return this.context.getBean(store.dataSourceBeanName(), DataSource.class).getConnection();
	}

	private static String storeSchemaList() {
		return Arrays.stream(StoreSchema.values())
				.map(store -> "'" + store.schema() + "'")
				.collect(Collectors.joining(", "));
	}
}

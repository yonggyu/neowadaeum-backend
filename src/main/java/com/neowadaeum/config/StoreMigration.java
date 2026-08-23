package com.neowadaeum.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/**
 * 스토어 하나의 Flyway 마이그레이션. 스토어마다 한 벌씩 등록된다 (§5.3).
 *
 * <p>Spring 의 Flyway 자동설정은 인스턴스를 1개만 만들고 {@code spring.flyway.*} 하나만 읽는다.
 * §5.3 은 스토어마다 경로가 분리되기를 요구하므로 자동설정을 제외하고 직접 만든다.
 *
 * <p><b>{@code flyway_schema_history} 도 스키마별로 분리된다.</b> 한 곳에 모으면 이력 테이블이
 * 크로스 스키마 참조점이 되어, 인스턴스 분리로 승격하는 순간 스토어들이 서로를 필요로 하게 된다.
 *
 * <p>마이그레이션은 자기 계정으로 실행된다. 다른 스키마를 건드리는 DDL 은 로컬에서 곧바로 권한 오류로
 * 터진다 — 운영에서 발견하는 것보다 낫다(§2.5).
 */
public class StoreMigration {

	private final StoreSchema store;

	private final Flyway flyway;

	public StoreMigration(StoreSchema store, DataSource dataSource) {
		this.store = store;
		this.flyway = Flyway.configure()
				.dataSource(dataSource)
				.schemas(store.schema())
				.defaultSchema(store.schema())
				.locations(store.migrationLocation())
				// 스키마 생성은 Flyway 의 일이 아니다. 로컬은 init 스크립트(§2.5), 운영은 DBA 소관이며
				// 각 계정에는 CREATE SCHEMA 권한이 없다. 스키마가 없으면 조용히 만들지 말고 실패해야 한다.
				.createSchemas(false)
				// clean 은 스키마를 통째로 비운다. 어떤 환경에서도 애플리케이션이 할 일이 아니다.
				.cleanDisabled(true)
				.load();
	}

	/** 빈 초기화 시점에 호출된다. */
	public void migrate() {
		this.flyway.migrate();
	}

	public StoreSchema store() {
		return this.store;
	}
}

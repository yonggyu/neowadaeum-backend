package com.neowadaeum.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * §5.3 Flyway 4세트. 스토어마다 마이그레이션 경로와 이력 테이블이 완전히 분리된다.
 *
 * <p>Spring 의 Flyway 자동설정은 인스턴스를 1개만 만들고 {@code spring.flyway.*} 하나만 읽는다.
 * 그래서 자동설정을 제외하고(§2.2 주석은 {@code application.yml} 참조) 여기서 직접 4벌을 만든다.
 *
 * <p><b>{@code flyway_schema_history} 도 스키마별로 분리된다.</b> 한 곳에 모으면 이력 테이블이
 * 크로스 스키마 참조점이 되어, 인스턴스 분리로 승격하는 순간 네 스토어가 서로를 필요로 하게 된다.
 *
 * <p>각 마이그레이션은 자기 계정으로 실행된다. 다른 스키마를 건드리는 DDL 은 로컬에서 곧바로 권한
 * 오류로 터진다 — 운영에서 발견하는 것보다 낫다(§2.5).
 */
@Configuration(proxyBeanMethods = false)
public class FlywayConfiguration {

	@Bean(initMethod = "migrate")
	public Flyway identityFlyway(@Qualifier("identityDataSource") DataSource dataSource) {
		return flyway(StoreSchema.IDENTITY, dataSource);
	}

	@Bean(initMethod = "migrate")
	public Flyway catalogFlyway(@Qualifier("catalogDataSource") DataSource dataSource) {
		return flyway(StoreSchema.CATALOG, dataSource);
	}

	@Bean(initMethod = "migrate")
	public Flyway playFlyway(@Qualifier("playDataSource") DataSource dataSource) {
		return flyway(StoreSchema.PLAY, dataSource);
	}

	@Bean(initMethod = "migrate")
	public Flyway promptLogFlyway(@Qualifier("promptLogDataSource") DataSource dataSource) {
		return flyway(StoreSchema.PROMPTLOG, dataSource);
	}

	private static Flyway flyway(StoreSchema store, DataSource dataSource) {
		return Flyway.configure()
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
}

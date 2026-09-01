package com.neowadaeum.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * §5.3 스토어 배선 — <b>설정된 스토어만</b> 등록한다 (ADR-0004).
 *
 * <p>스토어마다 별도 스키마 · 별도 계정 · 별도 커넥션 풀 · 별도 마이그레이션 경로다. 로컬에서는 컨테이너
 * 1개 안의 스키마 4개로 시작하고(§2.5), 트래픽·규제 요구에 따라 인스턴스 분리로 승격한다.
 * <b>승격 시 애플리케이션 코드는 변경이 없어야 한다</b> — 그래서 스키마 간 FK 와 JOIN 을 금지한다.
 *
 * <p><b>배선 로직은 스토어 수와 무관하게 {@link StoreDataSources} 한 곳에 있다.</b> 여기 있는 메서드들은
 * "어느 스토어가 존재하는가"만 선언한다. 스토어를 늘리고 줄일 때 바뀌는 것은 이 선언과 설정뿐이고,
 * 풀 구성 · 검증 · 마이그레이션 배선은 건드리지 않는다 — §5.3 의 "승격 시 코드 변경 없음"이 그 형태다.
 *
 * <p><b>조건부 등록({@code @ConditionalOnProperty})은 기각했다</b>(ADR-0004). 조건 평가가 설정 클래스 파싱
 * 시점에 일어나 {@code ${VAR}} 를 해석하는데, 이 프로젝트는 템플릿에 플레이스홀더를 두고(§7.3) 테스트는
 * {@code DynamicPropertyRegistrar} 로 값을 나중에 넣는다(§7.2). 두 관례와 구조적으로 충돌한다.
 * 등록 대상을 줄여 얻는 것도 실측 0.4초뿐이었다.
 *
 * <p>이 분리가 I-3 의 구조적 전제다. 비-Identity 스키마는 회원 식별정보를 담지 않고 {@code player_ref}
 * 만 담는다(§5.3). 스키마와 계정이 갈라져 있어야 "저장할 수 있는데 안 저장한다"가 아니라
 * "저장할 수 없다"가 된다.
 *
 * <p>§5.3 의 스토어는 넷으로 고정이다. 다섯 번째가 생기려면 스키마 · 계정 · {@link StoreSchema} 항목이
 * 함께 필요하므로 어차피 코드 변경이며, 그때 바뀌는 것은 이 파일의 선언 두 줄이다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StoreDataSourceProperties.class)
public class DataSourceConfiguration {

	@Bean
	public DataSource identityDataSource(StoreDataSourceProperties properties) {
		return StoreDataSources.create(StoreSchema.IDENTITY, properties.identity());
	}

	@Bean(initMethod = "migrate")
	public StoreMigration identityMigration(@Qualifier("identityDataSource") DataSource dataSource) {
		return new StoreMigration(StoreSchema.IDENTITY, dataSource);
	}

	@Bean
	public DataSource catalogDataSource(StoreDataSourceProperties properties) {
		return StoreDataSources.create(StoreSchema.CATALOG, properties.catalog());
	}

	@Bean(initMethod = "migrate")
	public StoreMigration catalogMigration(@Qualifier("catalogDataSource") DataSource dataSource) {
		return new StoreMigration(StoreSchema.CATALOG, dataSource);
	}

	@Bean
	public DataSource playDataSource(StoreDataSourceProperties properties) {
		return StoreDataSources.create(StoreSchema.PLAY, properties.play());
	}

	@Bean(initMethod = "migrate")
	public StoreMigration playMigration(@Qualifier("playDataSource") DataSource dataSource) {
		return new StoreMigration(StoreSchema.PLAY, dataSource);
	}

	@Bean
	public DataSource promptLogDataSource(StoreDataSourceProperties properties) {
		return StoreDataSources.create(StoreSchema.PROMPTLOG, properties.promptlog());
	}

	@Bean(initMethod = "migrate")
	public StoreMigration promptLogMigration(@Qualifier("promptLogDataSource") DataSource dataSource) {
		return new StoreMigration(StoreSchema.PROMPTLOG, dataSource);
	}
}

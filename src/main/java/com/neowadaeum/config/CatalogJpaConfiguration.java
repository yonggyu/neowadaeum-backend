package com.neowadaeum.config;

import jakarta.persistence.EntityManagerFactory;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@code catalog} 스토어의 JPA 배선 — EntityManagerFactory 1벌, TransactionManager 1벌 (B-05-1, B-08).
 *
 * <p><b>#20 의 마지막 벌이다.</b> {@code play}(S-2) · {@code promptlog}(B-11) · {@code identity}(B-07)
 * 에 이어 네 스토어가 모두 자기 EMF 를 갖게 된다.
 *
 * <p><b>이 배선이 없으면</b> catalog 엔티티가 생기는 순간 JPA 자동설정이 붙을 자리를 찾아 EMF 가
 * 하나 더 생기고, 네 스키마의 엔티티가 거기 묶여 <b>JPQL 한 줄로 크로스 스키마 조인이 열린다.</b>
 * FK 검증은 FK 만 보므로 그 경로를 잡지 못한다.
 *
 * <p><b>{@code @Primary} 를 붙이지 않는다.</b> 후보가 넷이므로 이름 없는 {@code @Transactional} 은
 * 부팅에서 실패한다: {@code @Transactional("catalogTransactionManager")} 로 명시해야 한다.
 *
 * <p>{@code StoryVersionFacade} 는 여전히 {@code JdbcClient} 를 쓴다. 턴 파이프라인이 한 번에
 * 읽는 경로이며, 이 작업의 범위는 <b>새 표의 엔티티</b>다 — 동작이 같은 코드를 옮기는 것은
 * 이 {@code B-xx} 에 포함하지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaRepositories(
		basePackages = { CatalogJpaConfiguration.CATALOG_PACKAGE,
				CatalogJpaConfiguration.AUTHORING_PACKAGE },
		entityManagerFactoryRef = "catalogEntityManagerFactory",
		transactionManagerRef = "catalogTransactionManager")
public class CatalogJpaConfiguration {

	/**
	 * 이 EMF 가 볼 수 있는 범위 — catalog 모듈 하나다 (§5.2 / §5.4). 문자열인 것은 의도다:
	 * {@code config} 가 {@code catalog} 의 내부 타입을 참조하면 Modulith 경계 검증이 잡는다.
	 */
	static final String CATALOG_PACKAGE = "com.neowadaeum.catalog";

	/**
	 * <b>authoring 은 catalog 스토어에 산다</b> (ADR-0002, B-10).
	 *
	 * <p>스키마를 나누지 않은 이유는 작품과 원고가 <b>같은 트랜잭션에서 움직이는 순간</b>이
	 * 있기 때문이다 — 승인이 곧 버전 발행이다 (B-56). 소유는 여전히 authoring 이며, 그것은
	 * 어느 모듈이 그 표를 고치는가의 문제다.
	 */
	static final String AUTHORING_PACKAGE = "com.neowadaeum.authoring";

	/** 엔티티와 마이그레이션이 어긋나면 부팅이 실패한다. 다른 세 스토어와 같은 값이다. */
	private static final Map<String, Object> JPA_PROPERTIES = Map.of("hibernate.hbm2ddl.auto", "validate");

	@Bean
	@DependsOn("catalogMigration")
	public LocalContainerEntityManagerFactoryBean catalogEntityManagerFactory(
			@Qualifier("catalogDataSource") DataSource dataSource) {
		LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
		factory.setDataSource(dataSource);
		factory.setPackagesToScan(CATALOG_PACKAGE, AUTHORING_PACKAGE);
		factory.setPersistenceUnitName(StoreSchema.CATALOG.schema());
		factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
		factory.setJpaPropertyMap(JPA_PROPERTIES);
		return factory;
	}

	@Bean
	public PlatformTransactionManager catalogTransactionManager(
			@Qualifier("catalogEntityManagerFactory") EntityManagerFactory entityManagerFactory) {
		return new JpaTransactionManager(entityManagerFactory);
	}
}

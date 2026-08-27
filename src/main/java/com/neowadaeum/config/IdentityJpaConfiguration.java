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
 * {@code identity} 스토어의 JPA 배선 — EntityManagerFactory 1벌, TransactionManager 1벌 (B-05-1, B-07).
 *
 * <p><b>#20 의 세 번째 벌이다.</b> {@code play}(S-2) · {@code promptlog}(B-11) 에 이어 셋이 됐고,
 * 남은 하나는 {@code catalog}(B-08)다.
 *
 * <p><b>이 배선이 없으면</b> identity 엔티티가 생기는 순간 JPA 자동설정이 붙을 자리를 찾아 EMF 가
 * 하나 더 생기고, {@code user} 와 {@code play_session} 이 한 메타모델에 들어간다. 그 순간
 * <b>JPQL 한 줄로 크로스 스키마 조인이 열린다</b> — FK 검증은 그 경로를 보지 못한다.
 *
 * <p><b>{@code @Primary} 를 붙이지 않는다.</b> 후보가 셋이므로 이름 없는 {@code @Transactional} 은
 * 부팅에서 실패한다: {@code @Transactional("identityTransactionManager")} 로 명시해야 한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaRepositories(
		basePackages = IdentityJpaConfiguration.IDENTITY_PACKAGE,
		entityManagerFactoryRef = "identityEntityManagerFactory",
		transactionManagerRef = "identityTransactionManager")
public class IdentityJpaConfiguration {

	/**
	 * 이 EMF 가 볼 수 있는 범위 — identity 모듈 하나다 (§5.2 / §5.4). 문자열인 것은 의도다:
	 * {@code config} 가 {@code identity} 의 내부 타입을 참조하면 Modulith 경계 검증이 잡는다.
	 */
	static final String IDENTITY_PACKAGE = "com.neowadaeum.identity";

	/** 엔티티와 마이그레이션이 어긋나면 부팅이 실패한다. 다른 두 스토어와 같은 값이다. */
	private static final Map<String, Object> JPA_PROPERTIES = Map.of("hibernate.hbm2ddl.auto", "validate");

	@Bean
	@DependsOn("identityMigration")
	public LocalContainerEntityManagerFactoryBean identityEntityManagerFactory(
			@Qualifier("identityDataSource") DataSource dataSource) {
		LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
		factory.setDataSource(dataSource);
		factory.setPackagesToScan(IDENTITY_PACKAGE);
		factory.setPersistenceUnitName(StoreSchema.IDENTITY.schema());
		factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
		factory.setJpaPropertyMap(JPA_PROPERTIES);
		return factory;
	}

	@Bean
	public PlatformTransactionManager identityTransactionManager(
			@Qualifier("identityEntityManagerFactory") EntityManagerFactory entityManagerFactory) {
		return new JpaTransactionManager(entityManagerFactory);
	}
}

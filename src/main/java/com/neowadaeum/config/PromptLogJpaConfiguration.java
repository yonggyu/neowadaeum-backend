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
 * {@code promptlog} 스토어의 JPA 배선 — EntityManagerFactory 1벌, TransactionManager 1벌 (B-05-1, B-11).
 *
 * <p><b>두 번째 EMF 다. 그것이 이 작업의 요점이다.</b> {@code PlayJpaConfiguration} 은 자기 주석에
 * 이렇게 적어 뒀다 — <i>"#20 의 완료 조건 중 '다른 스토어 엔티티를 참조하는 JPQL 이 매핑 단계에서
 * 거부된다'는 <b>비교 대상 엔티티가 없어 지금은 성립하지 않는다</b>"</i>. {@code ai_call_log} 가
 * 생기면서 그 비교 대상이 실재하게 됐고, 격리를 <b>동작으로</b> 확인할 수 있다
 * ({@code StoreIsolationTests}).
 *
 * <p><b>{@code @Primary} 를 붙이지 않는다.</b> {@code PlayJpaConfiguration} 과 같은 이유이며, 이제는
 * 그 이유가 실제로 작동한다 — 후보가 둘이므로 이름 없는 {@code @Transactional} 은 부팅에서 실패한다.
 * 서비스는 매니저를 <b>명시</b>해야 한다: {@code @Transactional("promptLogTransactionManager")}.
 *
 * <p><b>스캔 범위가 모듈 하나로 고정된다</b> (§5.2 / §5.4). {@code ai.log} 만 본다 — 더 넓히면
 * 한 EMF 가 두 스토어의 엔티티를 알게 되고 <b>JPQL 한 줄로 크로스 스키마 조인이 가능해진다.</b>
 * FK 검증은 FK 만 보므로 그 경로를 잡지 못한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaRepositories(
		basePackages = PromptLogJpaConfiguration.PROMPT_LOG_PACKAGE,
		entityManagerFactoryRef = "promptLogEntityManagerFactory",
		transactionManagerRef = "promptLogTransactionManager")
public class PromptLogJpaConfiguration {

	/**
	 * 이 EMF 가 볼 수 있는 범위.
	 *
	 * <p><b>{@code ai} 모듈 전체가 아니라 {@code ai.log} 다.</b> {@code ai} 의 나머지는 DTO 와
	 * 어댑터이며 엔티티가 아니다 — 범위를 모듈 루트로 잡으면 나중에 그쪽에 엔티티가 생겼을 때
	 * 조용히 이 EMF 에 딸려 들어온다.
	 *
	 * <p>문자열인 것은 의도다. {@code config} 가 {@code ai} 의 내부 타입을 직접 참조하면 Modulith
	 * 경계 검증이 잡는다. 배선은 이름으로 하고 컴파일 의존은 만들지 않는다.
	 */
	static final String PROMPT_LOG_PACKAGE = "com.neowadaeum.ai.log";

	/** 엔티티와 마이그레이션이 어긋나면 부팅이 실패한다. {@code PlayJpaConfiguration} 과 같은 값이다. */
	private static final Map<String, Object> JPA_PROPERTIES = Map.of("hibernate.hbm2ddl.auto", "validate");

	@Bean
	@DependsOn("promptLogMigration")
	public LocalContainerEntityManagerFactoryBean promptLogEntityManagerFactory(
			@Qualifier("promptLogDataSource") DataSource dataSource) {
		LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
		factory.setDataSource(dataSource);
		factory.setPackagesToScan(PROMPT_LOG_PACKAGE);
		factory.setPersistenceUnitName(StoreSchema.PROMPTLOG.schema());
		factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
		factory.setJpaPropertyMap(JPA_PROPERTIES);
		return factory;
	}

	@Bean
	public PlatformTransactionManager promptLogTransactionManager(
			@Qualifier("promptLogEntityManagerFactory") EntityManagerFactory entityManagerFactory) {
		return new JpaTransactionManager(entityManagerFactory);
	}
}

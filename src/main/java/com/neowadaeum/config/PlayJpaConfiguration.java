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
 * {@code play} 스토어의 JPA 배선 — EntityManagerFactory 1벌, TransactionManager 1벌.
 *
 * <p><b>왜 자동설정을 쓰지 않는가.</b> JPA 자동설정은 EMF 를 <b>1벌만</b> 만든다. 네 스키마의 엔티티가
 * 한 EMF 에 묶이면 JPQL 한 줄로 크로스 스키마 조인이 가능해지고, FK 검증은 FK 만 보므로 그 경로를
 * 잡지 못한다 (§5.3, #20). 그래서 {@code spring.autoconfigure.exclude} 로 막고 스토어마다 직접 만든다.
 *
 * <p><b>지금 play 하나뿐인 이유.</b> ADR-0004 의 수직 슬라이스에서 엔티티를 갖는 스토어는 play 하나다.
 * 나머지 3벌은 catalog · identity · promptlog 에 엔티티가 생기는 시점(B-07 / B-08 / B-11)에
 * <b>B-05-1(#20)</b> 이 완성한다. #20 의 완료 조건 중 "다른 스토어 엔티티를 참조하는 JPQL 이 매핑
 * 단계에서 거부된다"는 <b>비교 대상 엔티티가 없어 지금은 성립하지 않는다</b> — 그래서 #20 은 열어 둔다.
 *
 * <p><b>{@code @Primary} 를 붙이지 않는다.</b> DataSource 도, EMF 도, TransactionManager 도 마찬가지다.
 * 후보가 하나가 되는 순간 {@code @ConditionalOnSingleCandidate} 자동설정이 되살아난다
 * ({@link StoreDataSources}). 스토어가 하나뿐인 지금이 그 유혹이 가장 큰 시점이라 여기에 적어 둔다.
 * 두 번째 스토어가 붙을 때는 이미 그 위에 엔티티가 쌓여 있다.
 *
 * <p>따라서 서비스는 트랜잭션 매니저를 <b>명시</b>해야 한다 — {@code @Transactional("playTransactionManager")}.
 * 이름 없는 {@code @Transactional} 은 후보가 여럿이 되는 순간 부팅에서 실패한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaRepositories(
		basePackages = PlayJpaConfiguration.PLAY_PACKAGE,
		entityManagerFactoryRef = "playEntityManagerFactory",
		transactionManagerRef = "playTransactionManager")
public class PlayJpaConfiguration {

	/**
	 * 이 EMF 가 볼 수 있는 범위. <b>모듈 하나로 고정한다</b> (§5.2 / §5.4).
	 *
	 * <p>문자열인 것은 의도다. {@code config} 가 {@code play} 의 내부 타입을 직접 참조하면 Modulith
	 * 경계 검증이 잡는다. 배선은 이름으로 하고, 컴파일 의존은 만들지 않는다.
	 */
	static final String PLAY_PACKAGE = "com.neowadaeum.play";

	/**
	 * 스키마 검증을 켠다 — 엔티티와 마이그레이션이 어긋나면 <b>부팅이 실패한다</b>.
	 *
	 * <p>{@code none} 으로 두면 어긋남이 첫 쿼리까지 숨는다. {@code update} 는 금지다 — 애플리케이션이
	 * 스키마를 바꾸기 시작하면 Flyway 이력이 진실이기를 멈춘다.
	 */
	private static final Map<String, Object> JPA_PROPERTIES = Map.of("hibernate.hbm2ddl.auto", "validate");

	/**
	 * {@code playMigration} 다음에 만들어져야 한다. 검증({@code validate})은 테이블이 이미 있다는 전제이며,
	 * 순서가 뒤집히면 "엔티티가 틀렸다"처럼 보이는 부팅 실패가 난다 — 실제 원인은 순서다.
	 */
	@Bean
	@DependsOn("playMigration")
	public LocalContainerEntityManagerFactoryBean playEntityManagerFactory(
			@Qualifier("playDataSource") DataSource dataSource) {
		LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
		factory.setDataSource(dataSource);
		factory.setPackagesToScan(PLAY_PACKAGE);
		factory.setPersistenceUnitName(StoreSchema.PLAY.schema());
		factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
		factory.setJpaPropertyMap(JPA_PROPERTIES);
		return factory;
	}

	@Bean
	public PlatformTransactionManager playTransactionManager(
			@Qualifier("playEntityManagerFactory") EntityManagerFactory entityManagerFactory) {
		return new JpaTransactionManager(entityManagerFactory);
	}
}

package com.neowadaeum.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.ContainerTestBase;

import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.ClassUtils;

/**
 * B-05 완료 조건 3 — 자동설정 배선이 의도한 상태인지 <b>동작으로</b> 확인한다.
 *
 * <p>파일에 무엇이 적혀 있는지는 검증이 아니다. §2.2 — 존재하지 않는 FQCN 을
 * {@code spring.autoconfigure.exclude} 에 써도 Spring 은 오류를 내지 않고 조용히 무시한다.
 * Boot 4 에서 자동설정 클래스가 전부 기능별 패키지로 옮겨졌으므로 낡은 이름은 이렇게 무력화된다.
 * 그래서 이 테스트는 (1) 적힌 이름이 실재하는 자동설정 클래스인지, (2) 그 결과 컨텍스트에 어떤 빈이
 * 있고 없는지를 본다.
 *
 * <p>조사 결과(B-05) — B-05 이전에 쓰이던
 * {@code org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration} 은 Boot 4.1 에서도 유효한
 * 이름이었다. 즉 B-02~B-04 구간의 제외는 실제로 동작하고 있었다. 이 테스트는 사고의 기록이 아니라
 * 사후 확인이며, 같은 확인을 반복하지 않기 위해 남긴다.
 */
class AutoConfigurationExclusionTests extends ContainerTestBase {

	@Autowired
	private ApplicationContext context;

	@Autowired
	private Environment environment;

	/**
	 * §2.2 — 제외 목록의 이름이 전부 실재하는 자동설정 클래스여야 한다.
	 *
	 * <p>오타 하나가 조용히 통과하면 "제외했다"는 문장만 남고 자동설정은 그대로 살아 있다.
	 */
	@Test
	void S2_2_every_excluded_name_is_a_real_autoconfiguration_class() {
		List<String> excluded = Binder.get(this.environment)
				.bind("spring.autoconfigure.exclude", Bindable.listOf(String.class))
				.orElseThrow(() -> new AssertionError("spring.autoconfigure.exclude 가 비어 있다."));

		assertThat(excluded).isNotEmpty();
		for (String name : excluded) {
			ClassLoader classLoader = getClass().getClassLoader();
			assertThat(ClassUtils.isPresent(name, classLoader))
					.as("%s 가 클래스패스에 없다. 이름이 틀렸다면 Spring 은 조용히 무시한다 (§2.2)", name)
					.isTrue();
			assertThat(ClassUtils.resolveClassName(name, classLoader).getAnnotation(AutoConfiguration.class))
					.as("%s 는 자동설정 클래스가 아니다. 제외해도 아무 효과가 없다", name)
					.isNotNull();
		}
	}

	/**
	 * §2.5 / §5.3 — DataSource 는 {@code app.datasource.*} 기반 4개뿐이어야 한다.
	 *
	 * <p>자동설정이 만든 {@code dataSource} 빈이 섞이면 5번째 접속 경로가 생긴다.
	 */
	@Test
	void S5_3_only_the_four_store_datasources_exist() {
		assertThat(this.context.getBeanNamesForType(DataSource.class))
				.containsExactlyInAnyOrder("identityDataSource", "catalogDataSource", "playDataSource",
						"promptLogDataSource");
	}

	/**
	 * §5.3 — 마이그레이션은 <b>설정된 스토어 수만큼</b> 등록된다 (ADR-0004).
	 *
	 * <p>테스트는 네 스토어를 전부 설정하므로 4벌이다. 운영·개발은 설정한 만큼만 뜬다.
	 */
	@Test
	void S5_3_one_migration_per_configured_store() {
		assertThat(this.context.getBeanNamesForType(StoreMigration.class))
				.containsExactlyInAnyOrder("identityMigration", "catalogMigration", "playMigration",
						"promptLogMigration");
	}

	/** §2.2 — 자동설정이 만든 {@code Flyway} 빈이 하나도 없어야 한다. 우리는 직접 만든다. */
	@Test
	void S2_2_no_autoconfigured_flyway_bean_exists() {
		assertThat(this.context.getBeanNamesForType(Flyway.class)).isEmpty();
	}

	/**
	 * §5.3 — EntityManagerFactory 는 <b>스토어마다 1벌</b>이다. 넷이 다 찼다 (#20, B-08).
	 *
	 * <p>B-05 시점에는 0벌, #39 시점에는 play 하나였다. <b>이 테스트가 지키는 것은 개수가 아니다</b> —
	 * 자동설정이 만든 EMF 가 섞이지 않는다는 것이다. 자동설정 EMF 는 이름이 {@code entityManagerFactory}
	 * 이고 스캔 범위가 전체다. 그것이 하나 생기면 네 스키마의 엔티티가 거기 묶여 JPQL 한 줄로 크로스
	 * 스키마 조인이 가능해지며, FK 검증은 FK 만 보므로 그 경로를 잡지 못한다.
	 *
	 * <p><b>목록을 명시적으로 적는다.</b> 스토어가 엔티티를 갖게 될 때마다 이 줄을 <b>의식적으로</b>
	 * 고치게 하려는 것이다 — 개수만 세면 자동설정 EMF 가 하나 끼어도 숫자가 맞아떨어질 수 있다.
	 * <b>#20 은 이 지점에서 닫힌다</b> — 네 스토어가 모두 자기 EMF 를 갖는다.
	 */
	@Test
	void S5_3_entity_manager_factories_exist_per_store_with_entities() {
		assertThat(this.context.getBeanNamesForType(EntityManagerFactory.class))
				.containsExactlyInAnyOrder("playEntityManagerFactory", "promptLogEntityManagerFactory",
						"identityEntityManagerFactory", "catalogEntityManagerFactory")
				.as("자동설정 EMF 는 이름이 entityManagerFactory 다. 그것이 섞이면 스캔 범위가 전체가 된다")
				.doesNotContain("entityManagerFactory");
	}

	/**
	 * §5.3 — TransactionManager 도 스토어별이다.
	 *
	 * <p>자동설정이 하나 더 만들면 이름 없는 {@code @Transactional} 이 어느 스토어에 붙는지가 빈 등록
	 * 순서에 달린 문제가 된다. 후보가 여럿일 때 실패하는 편이 조용히 다른 스토어를 잡는 것보다 낫다.
	 *
	 * <p><b>후보가 여럿이 된 지금 그 성질이 실효를 갖는다.</b> 하나뿐일 때는 이름을 빠뜨려도
	 * 우연히 맞았다.
	 */
	@Test
	void S5_3_transaction_managers_exist_per_store_with_entities() {
		assertThat(this.context.getBeanNamesForType(PlatformTransactionManager.class))
				.containsExactlyInAnyOrder("playTransactionManager", "promptLogTransactionManager",
						"identityTransactionManager", "catalogTransactionManager")
				.as("자동설정 TM 은 이름이 transactionManager 다")
				.doesNotContain("transactionManager");
	}
}

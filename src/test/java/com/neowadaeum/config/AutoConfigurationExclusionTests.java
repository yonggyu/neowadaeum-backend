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

	/** §5.3 — Flyway 도 스토어별 4개뿐이어야 한다. 자동설정이 만든 5번째가 있으면 안 된다. */
	@Test
	void S5_3_only_the_four_store_flyway_instances_exist() {
		assertThat(this.context.getBeanNamesForType(Flyway.class))
				.containsExactlyInAnyOrder("identityFlyway", "catalogFlyway", "playFlyway", "promptLogFlyway");
	}

	/**
	 * B-05 범위 — EntityManagerFactory 는 아직 0벌이다.
	 *
	 * <p>1벌이 생기는 순간 네 스키마의 엔티티가 한 EMF 에 묶여 JPQL 한 줄로 크로스 스키마 조인이
	 * 가능해진다. FK 검증은 FK 만 보므로 그 경로를 잡지 못한다. 스토어별 4벌은 B-05-1 이다.
	 */
	@Test
	void B05_no_entity_manager_factory_exists_yet() {
		assertThat(this.context.getBeanNamesForType(EntityManagerFactory.class)).isEmpty();
	}
}

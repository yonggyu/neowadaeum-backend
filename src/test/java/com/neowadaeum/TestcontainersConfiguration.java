package com.neowadaeum;

import com.neowadaeum.config.StoreSchema;
import java.nio.file.Path;
import java.util.Locale;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * 테스트용 인프라. §7.2 에 따라 테스트 설정 yml 을 만들지 않고 런타임에 주입한다.
 *
 * <p><b>컨테이너는 1개, 스키마는 4개다.</b> §5.3 의 4-스토어 분리는 컨테이너 1개 안의 스키마 4개로
 * 시작하며(§2.5), 테스트도 같은 형태여야 한다. 컨테이너를 4개 띄우면 계정 권한 경계가 검증되지 않는다.
 *
 * <p>이미지 태그는 {@code docker-compose.yml} 에서 읽는다({@link ComposeImages}). 스키마 초기화 스크립트는
 * 로컬과 동일한 파일을 그대로 마운트한다 — 스크립트가 테스트에서만 다르게 동작하면 검증할 이유가 없다.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	/** 로컬과 같은 초기화 스크립트. 사본을 두지 않는다 (§2.5). */
	private static final Path INIT_SCRIPT = Path.of("docker/postgres/init/01-init-schemas.sh");

	private static final String INIT_SCRIPT_TARGET = "/docker-entrypoint-initdb.d/01-init-schemas.sh";

	/**
	 * PostgreSQL 컨테이너.
	 *
	 * <p>{@code @ServiceConnection} 을 쓰지 않는다. 그것은 {@code spring.datasource.*} 를 채우는데, 이
	 * 프로젝트는 그 프로퍼티를 쓰지 않고 {@code app.datasource.*} 4벌을 직접 정의한다(§2.5). 붙이면 쓰이지
	 * 않는 접속 정보가 하나 더 생겨 어느 쪽이 진짜인지 흐려진다.
	 *
	 * <p>스키마 계정 4개의 비밀번호는 컨테이너 슈퍼유저 비밀번호를 그대로 쓴다. 테스트 컨테이너와 함께
	 * 사라지는 값이며, 소스에 어떤 자격 증명 문자열도 남기지 않는다 (S-11).
	 */
	@Bean
	PostgreSQLContainer postgresContainer() {
		PostgreSQLContainer container = new PostgreSQLContainer(
				DockerImageName.parse(ComposeImages.of("postgres")))
				.withCopyFileToContainer(MountableFile.forHostPath(INIT_SCRIPT, 0755), INIT_SCRIPT_TARGET);

		for (StoreSchema store : StoreSchema.values()) {
			container.withEnv(store.schema().toUpperCase(Locale.ROOT) + "_DB_PASSWORD", container.getPassword());
		}
		return container;
	}

	@Bean
	@ServiceConnection(name = "redis")
	GenericContainer<?> redisContainer() {
		return new GenericContainer<>(DockerImageName.parse(ComposeImages.of("redis"))).withExposedPorts(6379);
	}

	/**
	 * §5.3 — 스토어 4개의 접속 정보를 런타임에 주입한다.
	 *
	 * <p>각 스토어는 <b>자기 계정</b>으로 붙는다. 슈퍼유저로 붙이면 통합 테스트가 전부 통과하면서
	 * "각 계정은 자기 스키마에만 권한을 갖는다"는 §5.3 의 전제만 검증되지 않는다.
	 */
	@Bean
	DynamicPropertyRegistrar storeDataSourceRegistrar(PostgreSQLContainer postgresContainer) {
		return registry -> {
			for (StoreSchema store : StoreSchema.values()) {
				String prefix = "app.datasource." + store.schema() + ".";
				registry.add(prefix + "url", () -> jdbcUrl(postgresContainer, store));
				registry.add(prefix + "username", () -> store.schema() + "_user");
				registry.add(prefix + "password", postgresContainer::getPassword);
			}
		};
	}

	/**
	 * {@code application.yml}의 {@code spring.data.redis.url: ${REDIS_URL}}은 런타임 전용이다. 테스트에는 `.env`가 없어
	 * 플레이스홀더가 그대로 남고 Redis URL 파싱이 깨진다. §7.2에 따라 테스트 설정 yml을 만들지 않고
	 * Testcontainers가 띄운 컨테이너 주소를 런타임에 주입한다.
	 */
	@Bean
	DynamicPropertyRegistrar redisUrlRegistrar(GenericContainer<?> redisContainer) {
		return registry -> registry.add("spring.data.redis.url",
				() -> "redis://%s:%d".formatted(redisContainer.getHost(), redisContainer.getMappedPort(6379)));
	}

	/** DataSourceConfiguration 이 {@code currentSchema} 를 요구한다. 빠지면 부팅이 실패한다. */
	private static String jdbcUrl(PostgreSQLContainer container, StoreSchema store) {
		String url = container.getJdbcUrl();
		return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + store.schema();
	}
}

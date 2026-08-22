package com.neowadaeum;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:latest"));
	}

	@Bean
	@ServiceConnection(name = "redis")
	GenericContainer<?> redisContainer() {
		return new GenericContainer<>(DockerImageName.parse("redis:latest")).withExposedPorts(6379);
	}

	/**
	 * `application.yml`의 `spring.data.redis.url: ${REDIS_URL}`은 런타임 전용이다. 테스트에는 `.env`가 없어
	 * 플레이스홀더가 그대로 남고 Redis URL 파싱이 깨진다. §7.2에 따라 테스트 설정 yml을 만들지 않고
	 * Testcontainers가 띄운 컨테이너 주소를 런타임에 주입한다.
	 */
	@Bean
	DynamicPropertyRegistrar redisUrlRegistrar(GenericContainer<?> redisContainer) {
		return registry -> registry.add("spring.data.redis.url",
				() -> "redis://%s:%d".formatted(redisContainer.getHost(), redisContainer.getMappedPort(6379)));
	}

}

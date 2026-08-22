package com.neowadaeum;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.util.ClassUtils;

/**
 * §5.2 패키지 구조와 §5.4 모듈 간 의존 규칙을 빌드에서 강제한다.
 *
 * <p>문서로만 있는 경계는 반드시 깨진다(§2.1). 이 테스트가 그 문서를 실행 가능한 규칙으로 만든다.
 */
class ModuleStructureTests {

	private static final ApplicationModules MODULES = ApplicationModules.of(NeowadaeumBackendApplication.class);

	/** §5.2 — 패키지 트리의 최상위 모듈 10개가 전부 인식되어야 한다. */
	@Test
	void S5_2_package_tree_declares_all_top_level_modules() {
		List<String> detected = MODULES.stream().map(module -> module.getIdentifier().toString()).sorted().toList();

		assertThat(detected).containsExactly(
				"admin", "ai", "authoring", "batch", "catalog",
				"common", "config", "identity", "play", "safety");
	}

	/** §5.4 — 허용되지 않은 모듈 간 의존이 있으면 실패한다. */
	@Test
	void S5_4_module_dependencies_are_within_allowed_boundaries() {
		MODULES.verify();
	}

	/**
	 * §2.5 / #1 회귀 방지 — {@code spring-modulith-starter-jpa}가 다시 들어오면 실패한다.
	 *
	 * <p>이 스타터의 {@code JpaEventPublicationAutoConfiguration}은 DataSource 유무와 무관하게
	 * EntityManager를 요구해 B-05 이전 구간의 기동을 막는다. 모듈 간 통신은 이벤트가 아니라
	 * Facade이므로(§5.4) 이 의존성은 영구 제거 대상이다.
	 */
	@Test
	void B02_spring_modulith_starter_jpa_is_absent_from_the_classpath() {
		boolean present = ClassUtils.isPresent(
				"org.springframework.modulith.events.jpa.JpaEventPublicationAutoConfiguration",
				getClass().getClassLoader());

		assertThat(present).isFalse();
	}
}

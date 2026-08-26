package com.neowadaeum;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.ApplicationModule;
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
	 * ADR-0006 — <b>턴 생성은 {@code play} 가 소유한 포트로 뒤집혔다.</b>
	 *
	 * <p>{@code verify()} 는 <b>실제 참조</b>가 선언을 넘는지만 본다. 선언 자체가 조용히 넓어지는 것은
	 * 잡지 못한다 — 그래서 선언을 여기에 박아 둔다. 특히 <b>{@code play → ai} 가 한 줄이라도 되살아나면
	 * 의존이 양방향이 되고</b>, ADR-0006 이 "순환이 없다"의 근거로 든 전제가 무너진다.
	 *
	 * <p>허용 대상이 {@code "play"} 가 아니라 {@code "play :: port"} 인 것도 함께 고정한다.
	 * 모듈 이름만 적으면 {@code ai} 가 {@code play} 의 다른 노출까지 조용히 쓸 수 있게 된다 —
	 * 열린 것은 <b>DTO 와 인터페이스뿐인 계약 패키지 하나</b>이고 엔티티·Repository 는 닫혀 있다.
	 */
	@Test
	void ADR0006_turn_generation_is_owned_by_play_and_implemented_by_ai() {
		assertThat(allowedDependenciesOf("play"))
				.as("ADR-0006 — ai 가 여기 들어오면 양방향이다. 턴 생성은 play :: port 로 뒤집었다")
				.containsExactlyInAnyOrder("common", "catalog :: query", "safety :: l2");

		assertThat(allowedDependenciesOf("ai"))
				.as("ADR-0006 — ai 는 play 의 계약 패키지 하나만 본다. 도메인 엔티티는 여전히 닫혀 있다")
				.containsExactlyInAnyOrder("common", "play :: port");

		assertThat(allowedDependenciesOf("safety"))
				.as("safety ← (도메인 모듈 참조 X). 블록리스트는 common/spi 로 주입받는다 (ADR-0002)")
				.containsExactly("common");
	}

	private static List<String> allowedDependenciesOf(String module) {
		try {
			ApplicationModule annotation = Class.forName("com.neowadaeum." + module + ".package-info")
					.getPackage().getAnnotation(ApplicationModule.class);
			return List.of(annotation.allowedDependencies());
		}
		catch (ClassNotFoundException ex) {
			throw new IllegalStateException("%s 모듈의 package-info 를 찾지 못했다".formatted(module), ex);
		}
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

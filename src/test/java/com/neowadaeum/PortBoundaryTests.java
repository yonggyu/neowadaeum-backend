package com.neowadaeum;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * <b>ADR-0006 의 경계를 빌드가 지킨다</b> (#95).
 *
 * <p>ADR-0006 은 {@code ai → play :: port} 를 열면서 <b>"열린 것은 DTO 와 인터페이스뿐인 계약
 * 패키지 하나"</b> 라고 적었다. 그 문장은 <b>오늘의 사실</b>이지 내일의 보장이 아니었다 —
 * {@code ModuleStructureTests} 가 쓰는 Modulith {@code verify()} 는 <b>모듈 단위</b>로만 보므로,
 * {@code play/port} 에 무엇을 더 넣든 {@code ai} 에 자동으로 보인다.
 *
 * <p>여기서 막는 것은 두 가지 드리프트다.
 *
 * <ol>
 *   <li><b>계약 패키지가 도메인을 끌어온다</b> — {@code play/port} 의 타입이 시그니처에
 *       {@code play.domain} 을 노출하면 {@code ai} 가 엔티티에 <b>컴파일 타임으로 닿는다</b>
 *   <li><b>계약 패키지가 계약이 아닌 것을 담는다</b> — 서비스나 엔티티가 들어오면 그 순간
 *       "DTO 와 인터페이스뿐"이 거짓이 된다
 * </ol>
 *
 * <p>ArchUnit 은 {@code spring-modulith-starter-test} 를 통해 이미 클래스패스에 있다. 새 의존성이
 * 아니다.
 */
class PortBoundaryTests {

	private static final String PORT = "com.neowadaeum.play.port..";

	private static final JavaClasses PRODUCTION = new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages("com.neowadaeum");

	/**
	 * <b>계약은 자기완결적이다.</b> {@code play/port} 는 {@code play} 의 다른 어떤 패키지도 모른다.
	 *
	 * <p>이 규칙이 1번 드리프트를 막는다 — 계약이 도메인을 모르면 계약을 통해 도메인이 새어 나갈
	 * 방법이 없다. {@code ai} 가 계약만 보고 있어도 <b>계약이 엔티티를 물고 있으면 소용없다.</b>
	 */
	@Test
	void ADR0006_the_port_contract_does_not_reference_play_internals() {
		noClasses().that().resideInAPackage(PORT)
				.should().dependOnClassesThat()
				.resideInAnyPackage("com.neowadaeum.play.domain..", "com.neowadaeum.play.repository..",
						"com.neowadaeum.play.api..", "com.neowadaeum.play.engine..",
						"com.neowadaeum.play.orchestrator..")
				.because("계약이 도메인을 물면 ai 가 그것을 통해 엔티티에 닿는다 (ADR-0006)")
				.check(PRODUCTION);
	}

	/**
	 * <b>{@code ai} 는 {@code play} 중 계약 패키지만 본다.</b>
	 *
	 * <p>Modulith 선언({@code allowedDependencies})은 모듈 단위라 이 성질을 <b>패키지 단위로</b>
	 * 좁히지 못한다. 선언이 {@code "play"} 로 넓어지는 실수도 여기서 걸린다.
	 */
	@Test
	void ADR0006_ai_sees_only_the_port_package_of_play() {
		noClasses().that().resideInAPackage("com.neowadaeum.ai..")
				.should().dependOnClassesThat(
						com.tngtech.archunit.base.DescribedPredicate.describe(
								"play 의 계약 패키지가 아닌 것",
								javaClass -> javaClass.getPackageName().startsWith("com.neowadaeum.play")
										&& !javaClass.getPackageName().equals("com.neowadaeum.play.port")))
				.because("ai 는 도메인 모듈을 참조하지 않는다 — 열린 것은 계약 패키지 하나다 (ADR-0006)")
				.check(PRODUCTION);
	}

	/**
	 * <b>계약 패키지에는 계약만 둔다.</b> record · interface · enum · 예외뿐이다.
	 *
	 * <p>2번 드리프트를 막는다. 서비스가 하나 들어오는 순간 <b>{@code ai} 가 {@code play} 의 동작을
	 * 부를 수 있게 되고</b>, 그때는 계약이 아니라 API 다.
	 */
	@Test
	void ADR0006_the_port_contract_holds_only_records_interfaces_and_exceptions() {
		classes().that().resideInAPackage(PORT)
				.and().areNotAnonymousClasses()
				.should(new ContractShapeCondition())
				.because("계약 패키지에 서비스나 엔티티가 들어오면 \"DTO 와 인터페이스뿐\"이 거짓이 된다")
				.check(PRODUCTION);
	}

	/** record · interface · enum · {@code RuntimeException} 하위만 허용한다. */
	private static final class ContractShapeCondition
			extends com.tngtech.archunit.lang.ArchCondition<com.tngtech.archunit.core.domain.JavaClass> {

		private ContractShapeCondition() {
			super("record · interface · enum · RuntimeException 이어야 한다");
		}

		@Override
		public void check(com.tngtech.archunit.core.domain.JavaClass item,
				com.tngtech.archunit.lang.ConditionEvents events) {

			boolean allowed = item.isRecord() || item.isInterface() || item.isEnum()
					|| item.isAssignableTo(RuntimeException.class)
					|| item.getSimpleName().equals("package-info");

			if (!allowed) {
				events.add(com.tngtech.archunit.lang.SimpleConditionEvent.violated(item,
						"%s 는 계약 타입이 아니다 — record · interface · enum · RuntimeException 만 둔다"
								.formatted(item.getName())));
			}
		}
	}
}

package com.neowadaeum.play.engine;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.play.domain.GameStateSnapshot;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * <b>GameState 에 들어오는 값의 출처를 못박는다</b> (#96, I-3, R4.1).
 *
 * <p>B-22 가 페이로드 화이트리스트에 불투명 서브트리 예외를 뚫었다 — {@code gameState} 안쪽은
 * 이름을 열거할 수 없어 검사하지 않는다. <b>그 예외를 정당화하는 것은 "그 값이 서버가 만든
 * 것이고 이미 다른 화이트리스트를 통과했다"는 전제</b>다.
 *
 * <p><b>그 전제는 지금 구조에만 걸려 있다.</b> 스냅샷을 쓰는 곳이 하나뿐이고 그것이 엔진 출력만
 * 쓰기 때문이며, {@code GameState.from} 은 저장된 JSON 을 <b>검증 없이 복원한다</b> — 두 번째
 * 쓰기 지점이 생기면 조용히 깨진다. 여기서 그 구조를 테스트가 지킨다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class GameStateProvenanceTests {

	private static final JavaClasses PRODUCTION = new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages("com.neowadaeum");

	/**
	 * <b>스냅샷을 만드는 곳은 오케스트레이터 하나다.</b>
	 *
	 * <p>두 번째 자리가 생기면 그쪽이 엔진을 거치지 않을 수 있고, 그때 화이트리스트의 예외가
	 * 근거를 잃는다. <b>증상은 저장이 아니라 다음 턴의 페이로드로 나타난다.</b>
	 */
	@Test
	void I3_only_the_orchestrator_creates_game_state_snapshots() {
		noClasses().that().resideOutsideOfPackage("com.neowadaeum.play.orchestrator..")
				.and().resideOutsideOfPackage("com.neowadaeum.play.domain..")
				.should().callMethodWhere(com.tngtech.archunit.base.DescribedPredicate.describe(
						"GameStateSnapshot.capture 호출",
						call -> call.getTarget().getOwner().isEquivalentTo(GameStateSnapshot.class)
								&& call.getTarget().getName().equals("capture")))
				.because("스냅샷이 엔진 출력만 담는다는 전제가 화이트리스트의 gameState 예외를 정당화한다 (#96)")
				.check(PRODUCTION);
	}

	/**
	 * <b>AI 가 제안한 비-스키마 키는 상태에 남지 않는다</b> (R4.1, §10.1-3).
	 *
	 * <p>{@code GameStateEngineTests} 가 같은 성질을 이미 본다. 여기서 다시 보는 것은 <b>화이트리스트
	 * 예외의 근거</b>로서다 — 이 성질이 깨지면 {@code gameState} 안쪽을 안 보는 결정도 함께 깨진다.
	 * 그 연결을 테스트 이름과 주석에 남겨 두지 않으면, 나중에 이 성질을 완화할 때 예외가 딸려 있다는
	 * 사실을 아무도 모른다.
	 */
	@Test
	void R4_1_a_key_outside_the_state_schema_never_reaches_the_stored_state() {
		JsonMapper json = JsonMapper.builder().build();
		// 시드와 같은 형태다 (V3__seed_slice_story.sql). 형태를 틀리면 스키마가 비어 모든 키가
		// 드롭되고, "드롭된다"는 단언이 거짓으로 통과한다 — 아래 양성 대조군이 그것을 막는다.
		StateSchema schema = StateSchema.from(json.readTree(
				"{\"affinity\":{\"yuna\":{\"min\":0,\"max\":100,\"maxDeltaPerTurn\":5}},"
						+ "\"flags\":[\"met_yuna\"]}"));

		StateChanges changes = StateChanges.from(json.readTree(
				"{\"affinity.yuna\":3,\"playerRef\":1,\"secret.debt\":9,\"flags.add\":[\"met_yuna\",\"forged\"]}"));

		GameState merged = new GameStateEngine().apply(GameState.initial(), schema, changes);
		Set<String> storedKeys = merged.toJson().propertyStream().map(java.util.Map.Entry::getKey)
				.collect(java.util.stream.Collectors.toSet());

		assertThat(storedKeys)
				.as("스키마 밖의 키가 상태에 남으면 화이트리스트의 gameState 예외가 근거를 잃는다")
				.doesNotContain("playerRef", "secret");
		assertThat(merged.toJson().path("flags").toString())
				.as("스키마 밖의 플래그도 남지 않는다")
				.doesNotContain("forged");
		// 양성 대조군 — 스키마 안의 키는 실제로 반영된다. 이것이 없으면 위의 단언들은
		// "스키마가 비어서 전부 드롭됐다"는 상태에서도 통과한다.
		assertThat(merged.toJson().path("affinity").path("yuna").asInt())
				.as("스키마 안의 키가 반영되지 않았다면 이 테스트는 아무것도 증명하지 못한다")
				.isEqualTo(3);
	}
}

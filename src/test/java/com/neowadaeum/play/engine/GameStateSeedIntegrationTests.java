package com.neowadaeum.play.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.play.domain.GameStateSnapshot;
import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.repository.GameStateSnapshotRepository;
import com.neowadaeum.play.repository.PlaySessionRepository;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-5 (#55) — 엔진이 <b>실제 시드</b>를 읽고 그 결과가 스냅샷으로 왕복하는지 확인한다.
 *
 * <p>단위 테스트는 스키마 JSON 을 테스트가 직접 적는다. 그러면 "엔진이 내가 적은 형식을 읽는다"만
 * 증명되고, <b>S-4 가 실제로 저장한 형식을 읽는다는 것은 증명되지 않는다.</b> 두 형식이 어긋나면
 * 증상은 S-9 에서 "상태가 안 변한다"로 나타나고 원인이 스키마 표기라는 것을 찾는 데 시간이 든다.
 *
 * <p>그래서 이 테스트는 `catalog` 시드에서 {@code state_schema} 를 <b>읽어 와서</b> 쓴다.
 * 스키마 간 조회가 아니라 테스트가 두 스토어를 각각 열어 보는 것이다 (§5.3 은 애플리케이션 코드의
 * 크로스 스키마 접근을 금지하며, 이 검증 방식은 {@code StoreSeparationTests} 와 같은 성격이다).
 */
class GameStateSeedIntegrationTests extends ContainerTestBase {

	private static final Instant NOW = Instant.parse("2026-08-23T04:05:06Z");

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final GameStateEngine engine = new GameStateEngine();

	private final ConditionEvaluator evaluator = new ConditionEvaluator();

	@Autowired
	@Qualifier("catalogDataSource")
	private DataSource catalog;

	@Autowired
	private PlaySessionRepository sessions;

	@Autowired
	private GameStateSnapshotRepository snapshots;

	/** R4.1 — 엔진이 시드의 {@code state_schema} 를 그대로 읽는다. */
	@Test
	void R4_1_engine_reads_the_state_schema_written_by_the_seed() throws SQLException {
		StateSchema schema = seedSchema();

		assertThat(schema.allowsNumeric("affinity.yuna")).isTrue();
		assertThat(schema.allowsFlag("met_yuna")).isTrue();
		assertThat(schema.allowsFlag("secret_route")).isFalse();
	}

	/** R4.2 — 시드가 선언한 델타 상한이 실제로 적용된다. 기본값으로 흘러가지 않는다. */
	@Test
	void R4_2_delta_cap_declared_by_the_seed_is_applied() throws SQLException {
		StateSchema schema = seedSchema();

		GameState result = this.engine.apply(GameState.initial().advanceTo(1, 1), schema,
				StateChanges.from(JSON.readTree("""
						{"affinity.yuna": 999}""")));

		assertThat(schema.numeric("affinity.yuna").maxDeltaPerTurn()).isEqualTo(5);
		assertThat(result.numerics()).containsEntry("affinity.yuna", 5);
	}

	/**
	 * I-5 — 엔진 출력이 스냅샷으로 저장되고 같은 값으로 돌아온다.
	 *
	 * <p>B-26 의 "스냅샷 저장"이 실제로 성립하는지 보는 지점이다. 턴 파이프라인 배선은 S-9 이며
	 * 여기서는 엔진 결과와 저장 형식이 맞물리는지만 확인한다.
	 */
	@Test
	void I5_engine_output_round_trips_through_a_snapshot() throws SQLException {
		StateSchema schema = seedSchema();
		PlaySession session = this.sessions.save(PlaySession.start(UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID(), "fixed", "scenario-v1", false, NOW));

		GameState state = this.engine.apply(GameState.initial().advanceTo(1, 1), schema,
				StateChanges.from(JSON.readTree("""
						{"affinity.yuna": 3, "flags.add": ["met_yuna"], "location": "교실"}""")));

		GameStateSnapshot saved = this.snapshots.save(
				GameStateSnapshot.capture(session.getId(), state.turn(), state.toJson().toString(), NOW));
		GameStateSnapshot found = this.snapshots.findById(saved.getId()).orElseThrow();

		assertThat(GameState.from(JSON.readTree(found.getState()))).isEqualTo(state);
		assertThat(found.getDeletedAt()).isNull();
	}

	// ── S-6 조건 평가기 × 시드 조건식 ─────────────────────

	/**
	 * R7.4 — 시드 챕터의 진입 조건이 실제로 평가된다.
	 *
	 * <p>단위 테스트는 조건식을 테스트가 직접 적는다. 그러면 "평가기가 내가 적은 문법을 읽는다"만
	 * 증명되고 <b>S-4 가 저장한 조건식을 읽는다는 것은 증명되지 않는다.</b> 어긋나면 S-7 에서
	 * "챕터가 안 넘어간다"로 나타나고 원인이 문법 표기라는 것을 찾는 데 시간이 든다.
	 */
	@Test
	void R7_4_seed_chapter_entry_conditions_are_evaluable() throws SQLException {
		JsonNode chapter2 = seedCondition("SELECT entry_condition FROM chapter_def WHERE chapter_no = 2");
		JsonNode chapter3 = seedCondition("SELECT entry_condition FROM chapter_def WHERE chapter_no = 3");

		GameState early = stateWith(2, Set.of());
		GameState met = stateWith(9, Set.of("met_yuna"));

		assertThat(this.evaluator.evaluate(chapter2, early)).isFalse();
		assertThat(this.evaluator.evaluate(chapter2, met)).isTrue();

		// 3장은 수치와 플래그를 함께 요구한다 — 수치만 채워서는 열리지 않는다.
		assertThat(this.evaluator.evaluate(chapter3, stateWith(9, Set.of()))).isFalse();
		assertThat(this.evaluator.evaluate(chapter3, met)).isTrue();
	}

	/** R7.6 — 조건부 엔딩의 조건이 평가된다. */
	@Test
	void R7_6_seed_conditional_ending_is_evaluable() throws SQLException {
		JsonNode ending = seedCondition("SELECT condition FROM ending_def WHERE ending_no = 1");

		assertThat(this.evaluator.evaluate(ending, stateWith(14, Set.of("shared_lunch")))).isFalse();
		assertThat(this.evaluator.evaluate(ending, stateWith(20, Set.of()))).isFalse();
		assertThat(this.evaluator.evaluate(ending, stateWith(20, Set.of("shared_lunch")))).isTrue();
	}

	/**
	 * §13-16 — 기본 엔딩은 {@code condition} 이 없고 <b>조건 판정에 참여하지 않는다.</b>
	 *
	 * <p>평가기에 넣는 것 자체가 잘못이므로 거부된다. 해석은 S-7 이 한다.
	 */
	@Test
	void S13_16_seed_default_ending_carries_no_condition_to_evaluate() throws SQLException {
		try (Connection connection = this.catalog.getConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery(
						"SELECT condition FROM ending_def WHERE is_default")) {
			assertThat(rows.next()).as("기본 엔딩이 없다").isTrue();
			assertThat(rows.getString(1)).isNull();
		}
	}

	/**
	 * 목표 수치까지 <b>여러 턴에 걸쳐</b> 쌓아 올린 상태를 만든다.
	 *
	 * <p>한 번에 목표값을 델타로 넣으면 {@code maxDeltaPerTurn} 에 잘려 다른 상태가 된다 —
	 * 초안에서 실제로 그렇게 틀렸고 이 테스트가 잡았다. 조건 평가의 입력은 <b>플레이로 도달
	 * 가능한 상태</b>여야 의미가 있으므로, 엔진을 반복 호출해 실제 누적 경로를 따른다.
	 */
	private GameState stateWith(int affinity, Set<String> flags) {
		StateSchema schema = seedSchemaUnchecked();
		int cap = schema.numeric("affinity.yuna").maxDeltaPerTurn();
		GameState state = GameState.initial().advanceTo(1, 3);

		while (state.numerics().getOrDefault("affinity.yuna", 0) < affinity) {
			int remaining = affinity - state.numerics().getOrDefault("affinity.yuna", 0);
			state = this.engine.apply(state, schema, StateChanges.from(JSON.readTree(
					"{\"affinity.yuna\": %d}".formatted(Math.min(cap, remaining)))));
		}

		for (String flag : flags) {
			state = this.engine.apply(state, schema, StateChanges.from(JSON.readTree(
					"{\"flags.add\": [\"%s\"]}".formatted(flag))));
		}

		return state;
	}

	private StateSchema seedSchemaUnchecked() {
		try {
			return seedSchema();
		}
		catch (SQLException ex) {
			throw new IllegalStateException("시드 state_schema 를 읽지 못했다", ex);
		}
	}

	private JsonNode seedCondition(String sql) throws SQLException {
		try (Connection connection = this.catalog.getConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery(sql)) {
			assertThat(rows.next()).as("행이 없다: %s", sql).isTrue();
			return JSON.readTree(rows.getString(1));
		}
	}

	private StateSchema seedSchema() throws SQLException {
		try (Connection connection = this.catalog.getConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("SELECT state_schema FROM story_version")) {
			assertThat(rows.next()).as("시드 작품 버전이 없다").isTrue();
			JsonNode node = JSON.readTree(rows.getString(1));
			return StateSchema.from(node);
		}
	}
}

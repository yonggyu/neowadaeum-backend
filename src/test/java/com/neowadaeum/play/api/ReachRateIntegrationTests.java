package com.neowadaeum.play.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.catalog.domain.EndingStat;
import com.neowadaeum.catalog.repository.EndingStatRepository;
import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.repository.GameStateSnapshotRepository;
import com.neowadaeum.play.repository.PlaySessionRepository;
import com.neowadaeum.play.repository.TurnRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * #161 — 도달률을 <b>읽기만</b> 한다 (R2.7, R2.8, I-20).
 *
 * <p>배치(B-39)가 채운 값을 응답에 낸다. <b>여기서 세지 않는다</b> — 조회 시점에 세면 인기 있는
 * 작품일수록 엔딩 화면이 느려지고, 그것이 I-20 이 금지한 것이다.
 *
 * <p>가장 중요한 단언은 <b>표본이 적으면 {@code null} 이다</b>(R2.8). UGC 작품에서 완주자가
 * 셋일 때의 도달률은 <b>특정인의 선택을 드러낸다.</b>
 */
class ReachRateIntegrationTests extends ContainerTestBase {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final UUID SEED_STORY = UUID.fromString("11111111-1111-4111-8111-000000000001");

	private static final UUID SEED_VERSION = UUID.fromString("11111111-1111-4111-8111-111111111111");

	private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private EndingStatRepository stats;

	@Autowired
	@org.springframework.beans.factory.annotation.Qualifier("playDataSource")
	private javax.sql.DataSource play;

	@Autowired
	private PlaySessionRepository sessions;

	@Autowired
	private TurnRepository turns;

	@Autowired
	private GameStateSnapshotRepository snapshots;

	@BeforeEach
	void clear() {
		this.snapshots.deleteAll();
		this.turns.deleteAll();
		this.sessions.deleteAll();
		this.stats.deleteAll();
	}

	@AfterEach
	void clearStats() {
		this.stats.deleteAll();
	}

	/** 엔딩이 아닌 턴에는 도달률이 없다 — 아직 어디에도 도달하지 않았다. */
	@Test
	void R2_7_a_non_ending_turn_has_no_reach_rate() throws Exception {
		UUID sessionId = startSession();

		assertThat(current(sessionId).path("reachRate").isNull()).isTrue();
	}

	/**
	 * <b>R2.8 — 표본이 50 미만이면 {@code null} 이다.</b> 0 이 아니다.
	 *
	 * <p>비율이 무의미하기도 하지만, UGC 작품에서 완주자가 셋일 때의 도달률은
	 * <b>특정인의 선택을 드러낸다.</b>
	 */
	@Test
	void R2_8_a_small_sample_yields_null_not_a_number() throws Exception {
		this.stats.save(EndingStat.of(SEED_STORY, 2, 3, 10, NOW));
		UUID sessionId = endedSession(2);

		assertThat(current(sessionId).path("reachRate").isNull()).isTrue();
	}

	/** 표본이 충분하면 값이 온다. 배치가 채운 값을 그대로 나눈 것이다. */
	@Test
	void R2_7_a_large_enough_sample_yields_the_rate() throws Exception {
		this.stats.save(EndingStat.of(SEED_STORY, 2, 25, 100, NOW));
		UUID sessionId = endedSession(2);

		assertThat(current(sessionId).path("reachRate").asDouble()).isEqualTo(0.25);
	}

	/** 경계값 — 정확히 50 이면 낸다. R2.8 은 "미만"이다. */
	@Test
	void R2_8_exactly_fifty_is_enough() throws Exception {
		this.stats.save(EndingStat.of(SEED_STORY, 2, 10, 50, NOW));
		UUID sessionId = endedSession(2);

		assertThat(current(sessionId).path("reachRate").asDouble()).isEqualTo(0.2);
	}

	/**
	 * <b>집계가 없으면 {@code null} 이다</b> (I-20).
	 *
	 * <p>배치가 아직 돌지 않은 상태이며, 조회가 대신 세지 않는다.
	 */
	@Test
	void I20_without_an_aggregate_the_rate_is_null() throws Exception {
		UUID sessionId = endedSession(2);

		assertThat(current(sessionId).path("reachRate").isNull()).isTrue();
	}

	// ── 보조 ────────────────────────────────────────────────

	private UUID startSession() throws Exception {
		MvcResult result = this.mockMvc.perform(post("/api/v1/stories/{storyId}/sessions", SEED_STORY)
						.with(asPlayer()))
				.andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString())
				.path("sessionId").asString());
	}

	/**
	 * 그 엔딩에 도달한 세션을 만든다.
	 *
	 * <p>실제로 40턴을 플레이하는 대신 마지막 턴의 엔딩만 붙인다 — 여기서 보는 것은
	 * <b>도달률이 어떻게 읽히는가</b>이지 엔딩 판정이 아니다(그쪽은 S-7 이 본다).
	 */
	private UUID endedSession(int endingNo) throws Exception {
		UUID sessionId = startSession();
		UUID endingId = endingIdOf(endingNo);

		// is_ending · ending_id 는 엔티티에서 updatable = false 다 (I-5 계열의 append-only 성질).
		// 리플렉션으로 필드를 바꿔도 Hibernate 가 UPDATE 에 싣지 않는다 — CI 가 그것을 알려 줬다.
		// 그래서 SQL 로 직접 만든다. 여기서 보는 것은 도달률이지 엔딩 판정이 아니다(S-7 이 본다).
		try (java.sql.Connection connection = this.play.getConnection();
				java.sql.PreparedStatement statement = connection.prepareStatement(
						"UPDATE turn SET is_ending = TRUE, ending_id = ? WHERE session_id = ?")) {
			statement.setObject(1, endingId);
			statement.setObject(2, sessionId);
			statement.executeUpdate();
		}
		catch (java.sql.SQLException ex) {
			throw new IllegalStateException(ex);
		}

		PlaySession session = this.sessions.findById(sessionId).orElseThrow();
		session.complete(endingId, NOW);
		this.sessions.saveAndFlush(session);
		return sessionId;
	}

	/** V5 시드의 엔딩 번호 → 식별자. 2번은 비시크릿이다. */
	private static UUID endingIdOf(int endingNo) {
		return switch (endingNo) {
			case 2 -> UUID.fromString("11111111-1111-4111-8111-0000000000e4");
			default -> throw new IllegalArgumentException("시드에 없는 엔딩 번호다: " + endingNo);
		};
	}

	private JsonNode current(UUID sessionId) throws Exception {
		MvcResult result = this.mockMvc.perform(get("/api/v1/sessions/{id}/current", sessionId)
						.with(asPlayer()))
				.andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		return JSON.readTree(result.getResponse().getContentAsString());
	}

}

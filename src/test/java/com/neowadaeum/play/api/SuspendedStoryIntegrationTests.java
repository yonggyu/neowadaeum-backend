package com.neowadaeum.play.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.play.repository.GameStateSnapshotRepository;
import com.neowadaeum.play.repository.PlaySessionRepository;
import com.neowadaeum.play.repository.StorySummaryRepository;
import com.neowadaeum.play.repository.TurnRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B-58 — <b>내려간 작품의 이야기는 읽을 수 있다</b> (R8.10, R13.3).
 *
 * <p>정지는 <b>작품에 대한 조치</b>이지 그 사람이 이미 읽은 것을 지우는 일이 아니다. 새 턴은
 * 막히고 기록은 남는다.
 */
class SuspendedStoryIntegrationTests extends ContainerTestBase {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final UUID SEED_STORY = UUID.fromString("11111111-1111-4111-8111-000000000001");

	@Autowired
	private MockMvc mvc;

	@Autowired
	@Qualifier("catalogDataSource")
	private DataSource catalog;

	@Autowired
	private PlaySessionRepository sessions;

	@Autowired
	private TurnRepository turns;

	@Autowired
	private GameStateSnapshotRepository snapshots;

	@Autowired
	private StorySummaryRepository summaries;

	@BeforeEach
	void clearPlayHistory() {
		this.summaries.deleteAll();
		this.snapshots.deleteAll();
		this.turns.deleteAll();
		this.sessions.deleteAll();
	}

	/** 시드 작품의 상태를 되돌린다 — 다른 테스트가 이 작품 위에서 돈다. */
	@AfterEach
	void restoreSeedStory() throws SQLException {
		setReviewStatus("approved");
	}

	/**
	 * <b>새 턴은 {@code 423} 이다</b> (R8.10, R13.3).
	 *
	 * <p>클라이언트는 이 코드로 <b>읽기 전용 안내</b>를 띄운다 — 실패가 아니라 상태다.
	 */
	@Test
	void R8_10_a_suspended_story_refuses_a_new_turn() throws Exception {
		UUID sessionId = givenSessionOnSeedStory();
		String choiceId = firstChoiceOf(sessionId);
		setReviewStatus("suspended");

		this.mvc.perform(advance(sessionId, choiceId, 1))
				.andExpect(status().isLocked())
				.andExpect(jsonPath("$.error").value("STORY_SUSPENDED"));
	}

	/**
	 * <b>기존 기록은 그대로 읽힌다</b> (R8.10).
	 *
	 * <p>정지가 기록을 지우면 그 사람은 <b>자기가 방금 읽은 것</b>을 다시 볼 수 없게 된다 —
	 * 그것은 작품에 대한 조치가 아니라 독자에 대한 조치다.
	 */
	@Test
	void R8_10_the_existing_record_is_still_readable() throws Exception {
		UUID sessionId = givenSessionOnSeedStory();
		setReviewStatus("suspended");

		this.mvc.perform(get("/api/v1/sessions/{id}/current", sessionId).with(asPlayer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.turnNo").value(1));
		this.mvc.perform(get("/api/v1/sessions/{id}/history", sessionId).with(asPlayer()))
				.andExpect(status().isOk());
	}

	/** Resume 은 {@code story_suspended} 다 (R13.3) — 클라이언트가 안내를 고를 근거다. */
	@Test
	void R13_3_resume_reports_story_suspended() throws Exception {
		UUID sessionId = givenSessionOnSeedStory();
		setReviewStatus("suspended");

		this.mvc.perform(get("/api/v1/sessions/{id}/resume", sessionId).with(asPlayer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sessionState").value("story_suspended"));
	}

	/** <b>정지된 작품으로는 새로 시작할 수 없다</b> (I-8) — 내려간 작품이 다시 열리면 안 된다. */
	@Test
	void R8_10_a_suspended_story_cannot_start_a_new_session() throws Exception {
		setReviewStatus("suspended");

		this.mvc.perform(post("/api/v1/stories/{storyId}/sessions", SEED_STORY).with(asPlayer()))
				.andExpect(status().isNotFound());
	}

	/**
	 * <b>정지가 풀리면 다시 이어갈 수 있다.</b>
	 *
	 * <p>정지는 되돌려질 수 있는 판단이다 (B-55 의 검수 큐가 그것을 판정한다) — 세션이
	 * 살아 있어야 <b>돌아올 자리</b>가 있다.
	 */
	@Test
	void R8_10_lifting_the_suspension_lets_the_session_continue() throws Exception {
		UUID sessionId = givenSessionOnSeedStory();
		String choiceId = firstChoiceOf(sessionId);
		setReviewStatus("suspended");
		this.mvc.perform(advance(sessionId, choiceId, 1)).andExpect(status().isLocked());

		setReviewStatus("approved");

		this.mvc.perform(advance(sessionId, choiceId, 1))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.turnNo").value(2));
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder advance(
			UUID sessionId, String choiceId, int turnNo) {
		return post("/api/v1/sessions/{sessionId}/turns", sessionId).with(asPlayer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"choiceId\":\"%s\",\"turnNo\":%d}".formatted(choiceId, turnNo));
	}

	private UUID givenSessionOnSeedStory() throws Exception {
		MvcResult result = this.mvc
				.perform(post("/api/v1/stories/{storyId}/sessions", SEED_STORY).with(asPlayer()))
				.andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString())
				.path("sessionId").asString());
	}

	private String firstChoiceOf(UUID sessionId) throws Exception {
		MvcResult result = this.mvc.perform(get("/api/v1/sessions/{id}/current", sessionId)
				.with(asPlayer())).andReturn();
		JsonNode turn = JSON.readTree(result.getResponse().getContentAsString());
		return turn.path("choices").get(0).path("choiceId").asString();
	}

	private void setReviewStatus(String reviewStatus) throws SQLException {
		try (Connection connection = this.catalog.getConnection();
				PreparedStatement statement = connection
						.prepareStatement("UPDATE story SET review_status = ? WHERE id = ?")) {
			statement.setString(1, reviewStatus);
			statement.setObject(2, SEED_STORY);
			statement.executeUpdate();
		}
	}
}

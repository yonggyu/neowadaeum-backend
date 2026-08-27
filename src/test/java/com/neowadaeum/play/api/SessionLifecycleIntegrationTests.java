package com.neowadaeum.play.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.domain.SessionStatus;
import com.neowadaeum.play.repository.GameStateSnapshotRepository;
import com.neowadaeum.play.repository.PlaySessionRepository;
import com.neowadaeum.play.repository.TurnRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * B-17(1/2) — 세션의 수명 (§13.4, §13-9).
 *
 * <p>여기서 확인하는 것 둘 — <b>다시 시작이 기존 것을 버리되 지우지 않는가</b>,
 * <b>삭제가 기록을 남기는가</b>.
 */
class SessionLifecycleIntegrationTests extends ContainerTestBase {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final UUID SEED_STORY = UUID.fromString("11111111-1111-4111-8111-000000000001");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PlaySessionRepository sessions;

	@Autowired
	private TurnRepository turns;

	@Autowired
	private GameStateSnapshotRepository snapshots;

	@BeforeEach
	void clearPlayHistory() {
		this.snapshots.deleteAll();
		this.turns.deleteAll();
		this.sessions.deleteAll();
	}

	/** §13-9 — 작품당 {@code active} 는 1개다. 두 번째는 409 다. */
	@Test
	void S13_9_a_second_session_without_restart_is_a_conflict() throws Exception {
		startSession(false);

		this.mockMvc.perform(post("/api/v1/stories/{storyId}/sessions", SEED_STORY).with(asPlayer()))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(409));
	}

	/**
	 * <b>{@code restart=true} 는 버리고 새로 만든다 — 지우지 않는다</b> (§13-9).
	 *
	 * <p>지나간 플레이는 기록이며 그 위에 턴·스냅샷·요약이 매달려 있다. 상태만 바꾸면
	 * "작품당 active 1개" 인덱스가 새 세션에 자리를 내준다.
	 */
	@Test
	void S13_9_restart_abandons_the_previous_session_and_keeps_it() throws Exception {
		UUID first = startSession(false);

		UUID second = startSession(true);

		assertThat(second).isNotEqualTo(first);
		assertThat(this.sessions.findById(first).orElseThrow().getStatus())
				.isEqualTo(SessionStatus.ABANDONED);
		assertThat(this.sessions.findById(second).orElseThrow().getStatus())
				.isEqualTo(SessionStatus.ACTIVE);
		assertThat(this.sessions.count()).as("버린 세션이 지워지면 안 된다").isEqualTo(2);
	}

	/** 버린 세션의 턴도 남는다 — 기록이기 때문이다 (R14.4 와 같은 이유). */
	@Test
	void S13_9_the_abandoned_sessions_turns_survive() throws Exception {
		UUID first = startSession(false);
		startSession(true);

		assertThat(this.turns.findFirstBySessionIdAndDeletedAtIsNullOrderByTurnNoDesc(first)).isPresent();
	}

	/** {@code restart=true} 는 진행 중인 세션이 없어도 그냥 만든다. */
	@Test
	void S13_9_restart_without_an_active_session_just_starts_one() throws Exception {
		assertThat(startSession(true)).isNotNull();
		assertThat(this.sessions.count()).isEqualTo(1);
	}

	/**
	 * <b>삭제는 soft delete 다</b> (§13.4, R14.4 와 같은 이유).
	 *
	 * <p>상태도 함께 {@code abandoned} 로 간다 — 남겨 두면 "작품당 active 1개" 인덱스가 지운
	 * 세션 때문에 새 세션을 막고, 사용자가 보기에는 <b>지웠는데 다시 시작할 수 없는 상태</b>다.
	 */
	@Test
	void S13_4_deleting_a_session_is_a_soft_delete() throws Exception {
		UUID sessionId = startSession(false);

		this.mockMvc.perform(delete("/api/v1/sessions/{sessionId}", sessionId).with(asPlayer()))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(204));

		PlaySession deleted = this.sessions.findById(sessionId).orElseThrow();
		assertThat(deleted.getDeletedAt()).isNotNull();
		assertThat(deleted.getStatus()).isEqualTo(SessionStatus.ABANDONED);
	}

	/** 지운 뒤에는 같은 작품을 다시 시작할 수 있다 — 그러지 못하면 지운 의미가 없다. */
	@Test
	void S13_4_a_new_session_can_start_after_deletion() throws Exception {
		UUID sessionId = startSession(false);
		this.mockMvc.perform(delete("/api/v1/sessions/{sessionId}", sessionId).with(asPlayer()));

		assertThat(startSession(false)).isNotEqualTo(sessionId);
	}

	/** 두 번 지워도 204 다 — 삭제는 상태를 맞추는 요청이다. */
	@Test
	void S13_4_deleting_twice_is_still_success() throws Exception {
		UUID sessionId = startSession(false);

		for (int attempt = 0; attempt < 2; attempt++) {
			this.mockMvc.perform(delete("/api/v1/sessions/{sessionId}", sessionId).with(asPlayer()))
					.andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(204));
		}
	}

	/** <b>남의 세션은 없는 것과 구분되지 않는다</b> (I-3). 지워지지도 않는다. */
	@Test
	void I3_another_member_cannot_delete_or_probe_the_session() throws Exception {
		UUID sessionId = startSession(false);

		this.mockMvc.perform(delete("/api/v1/sessions/{sessionId}", sessionId)
						.with(asPlayer(UUID.randomUUID())))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(404));
		this.mockMvc.perform(delete("/api/v1/sessions/{sessionId}", UUID.randomUUID()).with(asPlayer()))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(404));

		assertThat(this.sessions.findById(sessionId).orElseThrow().getDeletedAt()).isNull();
	}

	/** 토큰 없이는 401 이다. */
	@Test
	void S34_deleting_requires_a_token() throws Exception {
		this.mockMvc.perform(delete("/api/v1/sessions/{sessionId}", UUID.randomUUID()))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));
	}

	private UUID startSession(boolean restart) throws Exception {
		MvcResult result = this.mockMvc.perform(post("/api/v1/stories/{storyId}/sessions", SEED_STORY)
						.param("restart", String.valueOf(restart))
						.with(asPlayer()))
				.andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString())
				.path("sessionId").asString());
	}
}

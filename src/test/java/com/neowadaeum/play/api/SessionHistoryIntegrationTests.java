package com.neowadaeum.play.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.play.domain.Turn;
import com.neowadaeum.play.repository.GameStateSnapshotRepository;
import com.neowadaeum.play.repository.PlaySessionRepository;
import com.neowadaeum.play.repository.TurnRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B-35 — 지나간 턴을 다시 읽는다 (§13.6, 화면 2e).
 *
 * <p>가장 중요한 단언은 <b>응답 어디에도 {@code choiceId} 가 없다</b>는 것이다. 기록에서 받은
 * 식별자로 턴을 진행할 수 있으면 지나간 분기를 다시 고를 수 있게 된다 (I-1).
 */
class SessionHistoryIntegrationTests extends ContainerTestBase {

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

	/**
	 * <b>§13.6 — 읽기 전용이므로 {@code choiceId} 를 반환하지 않는다.</b>
	 *
	 * <p>본문 전체를 훑는다. "있어야 할 것"만 단언하면 값이 새어도 통과한다.
	 */
	@Test
	void S13_6_the_history_never_carries_a_choice_id() throws Exception {
		UUID sessionId = playTurns(3);

		String raw = historyRaw(sessionId, null, null);

		assertThat(raw).doesNotContain("choiceId");
		assertThat(JSON.readTree(raw).path("items")).isNotEmpty();
	}

	/** 고른 선택지의 <b>문구</b>가 온다 — 식별자가 아니다. */
	@Test
	void S13_6_the_chosen_choice_comes_as_text() throws Exception {
		UUID sessionId = playTurns(2);

		JsonNode items = history(sessionId, null, null).path("items");
		JsonNode firstTurn = itemOf(items, 1);

		assertThat(firstTurn.path("chosenChoiceText").asString()).isNotBlank();
		assertThat(firstTurn.path("paragraphs")).isNotEmpty();
		assertThat(firstTurn.path("chapterTitle").asString()).isNotBlank();
	}

	/**
	 * <b>§13-9 — {@code isPending} 은 마지막 턴이며 아직 선택이 없는 경우다.</b>
	 *
	 * <p>둘 다여야 한다. "선택이 없다"만 보면 과거 턴도 pending 이 되고, "마지막이다"만 보면
	 * 이미 고른 마지막 턴까지 pending 이 된다.
	 */
	@Test
	void S13_9_only_the_unanswered_last_turn_is_pending() throws Exception {
		UUID sessionId = playTurns(3);

		JsonNode items = history(sessionId, null, null).path("items");

		assertThat(itemOf(items, 3).path("isPending").asBoolean()).isTrue();
		assertThat(itemOf(items, 2).path("isPending").asBoolean()).isFalse();
		assertThat(itemOf(items, 1).path("isPending").asBoolean()).isFalse();
	}

	/** §13.6 — 역순이다. 화면이 "위로 스크롤해 더 읽기"이므로 이 방향이 맞다. */
	@Test
	void S13_6_items_come_newest_first() throws Exception {
		UUID sessionId = playTurns(3);

		JsonNode items = history(sessionId, null, null).path("items");

		assertThat(items.valueStream().map(item -> item.path("turnNo").asInt()).toList())
				.containsExactly(3, 2, 1);
	}

	/** 커서가 쪽을 잇는다 — 중복도 누락도 없다. 턴 번호가 세션 안에서 유일하기 때문이다 (I-6). */
	@Test
	void S13_6_the_cursor_pages_backwards_without_duplicates() throws Exception {
		UUID sessionId = playTurns(5);

		JsonNode first = history(sessionId, null, 2);
		JsonNode second = history(sessionId, first.path("nextCursor").asString(), 2);
		JsonNode third = history(sessionId, second.path("nextCursor").asString(), 2);

		List<Integer> seen = new ArrayList<>();
		for (JsonNode page : List.of(first, second, third)) {
			page.path("items").forEach(item -> seen.add(item.path("turnNo").asInt()));
		}
		assertThat(seen).containsExactly(5, 4, 3, 2, 1);
		assertThat(first.path("hasMore").asBoolean()).isTrue();
		assertThat(third.path("hasMore").asBoolean()).isFalse();
		assertThat(third.path("nextCursor").isNull()).isTrue();
	}

	/** <b>되돌려진 턴은 기록에 없다</b> (R14.4) — 없던 일이 이야기에 남으면 안 된다. */
	@Test
	void R14_4_a_rolled_back_turn_is_absent_from_the_history() throws Exception {
		UUID sessionId = playTurns(3);
		softDeleteTurn(sessionId, 2);

		JsonNode items = history(sessionId, null, null).path("items");

		assertThat(items.valueStream().map(item -> item.path("turnNo").asInt()).toList())
				.containsExactly(3, 1);
	}

	/** <b>남의 세션은 없는 것과 구분되지 않는다</b> (I-3). */
	@Test
	void I3_another_members_history_is_not_found() throws Exception {
		UUID sessionId = playTurns(1);

		assertThat(this.mockMvc.perform(get("/api/v1/sessions/{id}/history", sessionId)
						.with(asPlayer(UUID.randomUUID())))
				.andReturn().getResponse().getStatus()).isEqualTo(404);
	}

	/** 토큰 없이는 401 이다. */
	@Test
	void S34_history_requires_a_token() throws Exception {
		this.mockMvc.perform(get("/api/v1/sessions/{id}/history", UUID.randomUUID()))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));
	}

	/** 깨진 커서는 처음부터로 본다 — 형식을 바꾸는 순간 진행 중인 클라이언트가 깨지면 안 된다. */
	@Test
	void S13_6_a_broken_cursor_starts_from_the_newest() throws Exception {
		UUID sessionId = playTurns(2);

		assertThat(history(sessionId, "not-a-number", null).path("items")).hasSize(2);
	}

	// ── 보조 ────────────────────────────────────────────────

	/** 세션을 만들고 {@code turnCount} 개의 턴이 쌓일 때까지 진행한다. */
	private UUID playTurns(int turnCount) throws Exception {
		MvcResult started = this.mockMvc.perform(post("/api/v1/stories/{storyId}/sessions", SEED_STORY)
						.with(asPlayer()))
				.andReturn();
		assertThat(started.getResponse().getStatus()).isEqualTo(201);
		JsonNode body = JSON.readTree(started.getResponse().getContentAsString());
		UUID sessionId = UUID.fromString(body.path("sessionId").asString());

		JsonNode turn = body.path("turn");
		while (turn.path("turnNo").asInt() < turnCount) {
			MvcResult next = this.mockMvc.perform(post("/api/v1/sessions/{id}/turns", sessionId)
							.with(asPlayer())
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"choiceId\":\"%s\",\"turnNo\":%d}".formatted(
									turn.path("choices").get(0).path("choiceId").asString(),
									turn.path("turnNo").asInt())))
					.andReturn();
			assertThat(next.getResponse().getStatus()).isEqualTo(200);
			turn = JSON.readTree(next.getResponse().getContentAsString());
		}
		return sessionId;
	}

	/** 롤백은 B-42 다. 여기서는 그 결과 상태만 만든다. */
	private void softDeleteTurn(UUID sessionId, int turnNo) {
		Turn turn = this.turns.findBySessionIdAndDeletedAtIsNullOrderByTurnNoDesc(sessionId,
						org.springframework.data.domain.Limit.of(50)).stream()
				.filter(candidate -> candidate.getTurnNo() == turnNo)
				.findFirst()
				.orElseThrow();
		try {
			java.lang.reflect.Field deletedAt = Turn.class.getDeclaredField("deletedAt");
			deletedAt.setAccessible(true);
			deletedAt.set(turn, java.time.Instant.now());
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
		this.turns.saveAndFlush(turn);
	}

	private JsonNode history(UUID sessionId, String cursor, Integer limit) throws Exception {
		return JSON.readTree(historyRaw(sessionId, cursor, limit));
	}

	private String historyRaw(UUID sessionId, String cursor, Integer limit) throws Exception {
		var request = get("/api/v1/sessions/{id}/history", sessionId).with(asPlayer());
		if (cursor != null) {
			request = request.param("cursor", cursor);
		}
		if (limit != null) {
			request = request.param("limit", String.valueOf(limit));
		}
		MvcResult result = this.mockMvc.perform(request).andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		return result.getResponse().getContentAsString();
	}

	private static JsonNode itemOf(JsonNode items, int turnNo) {
		return items.valueStream()
				.filter(item -> item.path("turnNo").asInt() == turnNo)
				.findFirst()
				.orElseThrow(() -> new AssertionError("턴 %d 이 기록에 없다".formatted(turnNo)));
	}
}

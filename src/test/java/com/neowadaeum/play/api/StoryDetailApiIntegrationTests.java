package com.neowadaeum.play.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.catalog.domain.ServiceConfig;
import com.neowadaeum.catalog.repository.ServiceConfigRepository;
import com.neowadaeum.play.repository.GameStateSnapshotRepository;
import com.neowadaeum.play.repository.PlaySessionRepository;
import com.neowadaeum.play.repository.TurnRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B-16 — 작품 상세 화면(2.2)이 <b>밖에서</b> 온다 (§13.3).
 *
 * <p>여기서 확인하는 것 셋 — <b>상수 등급</b>(I-19), <b>시크릿을 뺀 엔딩 수</b>(R7.11),
 * <b>보이지 않아야 할 작품이 없는 것과 구분되지 않는다</b>(I-8).
 */
class StoryDetailApiIntegrationTests extends ContainerTestBase {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final UUID SEED_STORY = UUID.fromString("11111111-1111-4111-8111-000000000001");

	private static final UUID SEED_VERSION = UUID.fromString("11111111-1111-4111-8111-111111111111");

	@Autowired
	private MockMvc mockMvc;

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
	private ServiceConfigRepository configs;

	private final List<UUID> created = new ArrayList<>();

	@BeforeEach
	void clearPlayHistory() {
		this.snapshots.deleteAll();
		this.turns.deleteAll();
		this.sessions.deleteAll();
	}

	@AfterEach
	void removeCreated() throws SQLException {
		try (Connection connection = this.catalog.getConnection()) {
			for (UUID id : this.created) {
				try (PreparedStatement statement = connection
						.prepareStatement("DELETE FROM story WHERE id = ?")) {
					statement.setObject(1, id);
					statement.executeUpdate();
				}
			}
		}
		this.created.clear();
	}

	/**
	 * <b>토큰 없이도 열린다</b> (§13-54, 이슈 #306).
	 *
	 * <p>목록에서 들어온 사람이 로그인 없이 작품을 볼 수 있다 — 로그인은 플레이하려 할 때 한다.
	 */
	@Test
	void S13_54_the_detail_opens_without_a_token() throws Exception {
		MvcResult result = this.mockMvc.perform(get("/api/v1/stories/{storyId}", SEED_STORY)).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
		assertThat(body.path("story").path("title").asString()).isNotBlank();
		assertThat(body.path("story").path("ageRating").asString()).isEqualTo("15세 이용가");
	}

	/**
	 * <b>익명 응답에서 {@code mySession} 이 {@code null} 이다</b> (§13-54).
	 *
	 * <p>세션을 만들어 둔 뒤 <b>토큰 없이</b> 부른다 — 주인을 모르는 요청에 남의 세션이 실리지
	 * 않는다 (I-3). 세션이 없는 회원과 <b>같은 모양</b>이라 클라이언트가 분기를 더 갖지 않는다.
	 */
	@Test
	void S13_54_an_anonymous_detail_carries_no_my_session() throws Exception {
		this.mockMvc.perform(post("/api/v1/stories/{storyId}/sessions", SEED_STORY).with(asPlayer()))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(201));

		MvcResult result = this.mockMvc.perform(get("/api/v1/stories/{storyId}", SEED_STORY)).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(JSON.readTree(result.getResponse().getContentAsString()).path("mySession").isNull())
				.isTrue();
	}

	/**
	 * <b>I-8 — 익명에게도 볼 수 없는 작품은 없는 것과 구분되지 않는다</b> (§13-54).
	 *
	 * <p><b>이 작업에서 가장 중요한 단언이다.</b> 인증을 걷어내면서 노출 조건이 느슨해졌다면
	 * 여기서 드러난다 — 검수 대기 · 비공개 · 정지된 작품이 전부 {@code 404} 여야 하고,
	 * {@code 403} 이면 <b>id 하나로 그 작품의 존재가 확인된다.</b>
	 */
	@Test
	void I8_an_invisible_story_is_not_found_for_an_anonymous_request_too() throws Exception {
		UUID pending = insertStory("user", "public", "pending");
		UUID privateStory = insertStory("user", "private", "approved");
		UUID suspended = insertStory("user", "public", "suspended");

		assertThat(anonymousStatusOf(pending)).isEqualTo(404);
		assertThat(anonymousStatusOf(privateStory)).isEqualTo(404);
		assertThat(anonymousStatusOf(suspended)).isEqualTo(404);
		assertThat(anonymousStatusOf(UUID.randomUUID())).isEqualTo(404);
	}

	/** 승인·공개된 사용자 작품은 익명에게도 보인다 — 가리는 것은 조건이지 인증이 아니다 (§13-54). */
	@Test
	void I8_an_approved_public_user_story_is_visible_to_an_anonymous_request() throws Exception {
		UUID userStory = insertStory("user", "public", "approved");

		assertThat(anonymousStatusOf(userStory)).isEqualTo(200);
	}

	/**
	 * <b>I-19 · R10.1 — {@code ageRating} 은 상수다.</b>
	 *
	 * <p>작품별 등급 컬럼을 두는 순간 등급별 프롬프트·검수 기준·본인인증이 함께 따라온다 (R10.5).
	 */
	@Test
	void I19_the_age_rating_is_a_constant() throws Exception {
		assertThat(detail(SEED_STORY).path("story").path("ageRating").asString()).isEqualTo("15세 이용가");
	}

	/**
	 * <b>R7.11 — 시크릿 엔딩은 개수에도 들어가지 않는다.</b>
	 *
	 * <p>시드는 엔딩 5개 중 하나가 시크릿이다. 5 가 나오면 <b>개수만으로 존재가 새는</b> 것이다.
	 */
	@Test
	void R7_11_secret_endings_are_not_counted() throws Exception {
		JsonNode story = detail(SEED_STORY).path("story");

		assertThat(story.path("totalEndings").asInt()).isEqualTo(4);
		assertThat(story.path("totalChapters").asInt()).isEqualTo(6);
	}

	/** §13.3 — 상세에 보이는 인물만 온다. 프롬프트 재료는 응답에 없다. */
	@Test
	void S13_3_characters_come_without_their_prompt_material() throws Exception {
		JsonNode body = detail(SEED_STORY);

		assertThat(body.path("characters")).isNotEmpty();
		assertThat(body.path("characters").get(0).path("name").asString()).isNotBlank();
		assertThat(body.toString())
				.as("persona_prompt 는 프롬프트의 재료이지 화면의 것이 아니다")
				.doesNotContain("personaPrompt");
	}

	/**
	 * <b>§13-7 · I-3 — 작성자 식별자가 응답에 없다.</b>
	 *
	 * <p>공식 작품이므로 표시명이 비어 있다. 그 자리에 {@code playerRef} 를 대신 넣지 않는다.
	 */
	@Test
	void S13_7_the_response_carries_no_player_ref() throws Exception {
		JsonNode body = detail(SEED_STORY);

		assertThat(body.path("story").path("authorType").asString()).isEqualTo("official");
		assertThat(body.path("story").path("authorDisplayName").isNull()).isTrue();
		assertThat(body.toString()).doesNotContain("playerRef").doesNotContain("authorRef");
	}

	/** 세션이 없으면 {@code mySession} 은 {@code null} 이다 — 빈 객체가 아니다. */
	@Test
	void S13_3_my_session_is_null_without_a_session() throws Exception {
		assertThat(detail(SEED_STORY).path("mySession").isNull()).isTrue();
	}

	/** 세션을 시작하면 그 요약이 온다 (I-4 — 고정한 버전의 챕터 제목). */
	@Test
	void S13_3_a_started_session_appears_as_my_session() throws Exception {
		this.mockMvc.perform(post("/api/v1/stories/{storyId}/sessions", SEED_STORY).with(asPlayer()))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(201));

		JsonNode mySession = detail(SEED_STORY).path("mySession");

		assertThat(mySession.path("sessionId").asString()).isNotBlank();
		assertThat(mySession.path("chapterNo").asInt()).isEqualTo(1);
		assertThat(mySession.path("chapterTitle").asString()).isNotBlank();
	}

	/** <b>남의 세션은 보이지 않는다</b> (I-3). */
	@Test
	void I3_another_members_session_is_not_my_session() throws Exception {
		this.mockMvc.perform(post("/api/v1/stories/{storyId}/sessions", SEED_STORY).with(asPlayer()))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(201));

		MvcResult result = this.mockMvc
				.perform(get("/api/v1/stories/{storyId}", SEED_STORY).with(asPlayer(UUID.randomUUID())))
				.andReturn();

		assertThat(JSON.readTree(result.getResponse().getContentAsString()).path("mySession").isNull())
				.isTrue();
	}

	/**
	 * <b>I-8 — 볼 수 없는 작품은 없는 것과 구분되지 않는다.</b>
	 *
	 * <p>구분하면 승인 대기 중인 작품의 존재가 <b>id 하나로 확인된다.</b> 목록에서 가려 놓고
	 * 상세만 열어 두면 가린 의미가 사라진다.
	 */
	@Test
	void I8_an_invisible_story_is_indistinguishable_from_a_missing_one() throws Exception {
		UUID pending = insertStory("user", "public", "pending");
		UUID privateStory = insertStory("user", "private", "approved");

		assertThat(statusOf(pending)).isEqualTo(404);
		assertThat(statusOf(privateStory)).isEqualTo(404);
		assertThat(statusOf(UUID.randomUUID())).isEqualTo(404);
	}

	/**
	 * <b>R11.1 — 고지 문구가 이 응답에 실려 온다</b> (#257).
	 *
	 * <p>Footer 가 문구를 상시 표시한다. 여기서 주지 않으면 클라이언트가 화면마다
	 * {@code /landing} 을 한 번 더 부르고, 두 응답의 캐시 수명이 갈리면 <b>같은 화면에서 다른
	 * 문구</b>가 보인다.
	 */
	@Test
	void R11_1_the_story_detail_carries_the_notice_text() throws Exception {
		assertThat(detail(SEED_STORY).path("noticeText").asString()).isEqualTo(NOTICE);
	}

	/**
	 * <b>문구가 없으면 화면을 내보내지 않는다</b> (§11, R11.1) — 랜딩과 같은 판단이다.
	 *
	 * <p>빈 문자열로 흡수하면 <b>고지가 없는 상태가 정상으로 보인다.</b>
	 */
	@Test
	void R11_1_the_story_detail_fails_when_the_notice_is_not_configured() throws Exception {
		this.configs.deleteById(NOTICE_KEY);

		assertThat(statusOf(SEED_STORY)).isEqualTo(500);
	}

	/** 승인·공개된 사용자 작품은 보인다 — 가리는 것은 조건이지 종류가 아니다. */
	@Test
	void R2_3_an_approved_public_user_story_is_visible() throws Exception {
		UUID userStory = insertStory("user", "public", "approved");

		assertThat(statusOf(userStory)).isEqualTo(200);
	}

	private JsonNode detail(UUID storyId) throws Exception {
		MvcResult result = this.mockMvc.perform(get("/api/v1/stories/{storyId}", storyId).with(asPlayer()))
				.andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		return JSON.readTree(result.getResponse().getContentAsString());
	}

	/** 토큰을 싣지 않는다 (§13-54) — 익명 요청의 판정을 그대로 본다. */
	private int anonymousStatusOf(UUID storyId) throws Exception {
		return this.mockMvc.perform(get("/api/v1/stories/{storyId}", storyId)).andReturn().getResponse()
				.getStatus();
	}

	private int statusOf(UUID storyId) throws Exception {
		return this.mockMvc.perform(get("/api/v1/stories/{storyId}", storyId).with(asPlayer()))
				.andReturn().getResponse().getStatus();
	}

	/**
	 * 파사드가 막는지 보려면 막힐 데이터를 만들 수 있어야 한다.
	 *
	 * <p><b>#269 — {@code user} 는 {@code author_ref} 없이 존재할 수 없다.</b> 이 메서드는
	 * 항상 {@code authorType = "user"}로만 불린다. 무작위 {@code UUID}는 개별 단언과
	 * 무관하고, 제약(R13.1)을 만족시키는 것이 목적이다.
	 */
	private UUID insertStory(String authorType, String visibility, String reviewStatus) {
		UUID id = UUID.randomUUID();
		try (Connection connection = this.catalog.getConnection();
				PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO story (id, slug, title, short_desc, description, world_intro,
						                   author_type, author_ref, visibility, review_status,
						                   current_version_id, published_at, created_at)
						VALUES (?, ?, '테스트 작품', '한 줄', '설명', '세계관', ?, ?, ?, ?, ?, ?, ?)
						""")) {
			statement.setObject(1, id);
			statement.setString(2, "detail-" + id);
			statement.setString(3, authorType);
			statement.setObject(4, UUID.randomUUID());
			statement.setString(5, visibility);
			statement.setString(6, reviewStatus);
			statement.setObject(7, SEED_VERSION);
			statement.setTimestamp(8, java.sql.Timestamp.from(Instant.parse("2026-08-20T00:00:00Z")));
			statement.setTimestamp(9, java.sql.Timestamp.from(Instant.parse("2026-08-20T00:00:00Z")));
			statement.executeUpdate();
		}
		catch (SQLException ex) {
			throw new IllegalStateException(ex);
		}
		this.created.add(id);
		return id;
	}
}

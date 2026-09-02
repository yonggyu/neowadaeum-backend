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
 * B-15(2/2) — 라이브러리 화면(2.1)이 <b>밖에서</b> 온다 (§13.2).
 *
 * <p>이 화면은 두 스토어를 담는다 — 작품은 catalog, 이어하기는 play 다. 여기서 확인하는 것은
 * <b>둘이 한 응답으로 합쳐지는가</b>와 <b>합치면서 규칙이 지켜지는가</b>다.
 */
class LibraryApiIntegrationTests extends ContainerTestBase {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final UUID SEED_STORY = UUID.fromString("11111111-1111-4111-8111-000000000001");

	private static final String NOTICE_KEY = "ai.notice";

	private static final String NOTICE = "이 이야기는 AI가 생성합니다.";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PlaySessionRepository sessions;

	@Autowired
	private TurnRepository turns;

	@Autowired
	private GameStateSnapshotRepository snapshots;

	@Autowired
	private ServiceConfigRepository configs;

	@BeforeEach
	void clearPlayHistory() {
		this.snapshots.deleteAll();
		this.turns.deleteAll();
		this.sessions.deleteAll();
	}

	/** 고지 문구가 없으면 이 화면은 열리지 않는다 (R11.1, #257) — 설정을 먼저 놓는다. */
	@BeforeEach
	void configureNotice() {
		this.configs.save(ServiceConfig.of(NOTICE_KEY,
				"{\"version\":\"2026-07-21\",\"text\":\"%s\"}".formatted(NOTICE),
				Instant.parse("2026-08-27T00:00:00Z")));
	}

	@AfterEach
	void clearNotice() {
		this.configs.deleteById(NOTICE_KEY);
	}

	/** 토큰 없이는 401 이다 — 이어하기가 자기 것이려면 누구인지 알아야 한다 (#34). */
	@Test
	void S34_the_library_requires_a_token() throws Exception {
		this.mockMvc.perform(get("/api/v1/library"))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));
	}

	/** §13.2 — 장르 · 섹션 · 이어하기 세 덩어리가 한 응답에 온다. */
	@Test
	void S13_2_the_library_returns_genres_sections_and_continue_sessions() throws Exception {
		JsonNode body = library();

		assertThat(body.path("genres")).hasSize(5);
		assertThat(body.path("genres").get(0).path("genreId").asString()).isEqualTo("romance");
		assertThat(body.path("continueSessions")).isEmpty();
		assertThat(sectionKeys(body)).contains("recommended", "community");
	}

	/**
	 * <b>빈 장르 섹션을 만들지 않는다.</b>
	 *
	 * <p>장르는 다섯인데 시드 작품이 쓰는 것은 둘이다. 다섯 개를 전부 만들면 화면에 빈 섹션이
	 * 셋 남고, 조회도 그만큼 나간다 (§15).
	 */
	@Test
	void S13_2_only_genres_the_catalog_actually_uses_become_sections() throws Exception {
		assertThat(sectionKeys(library()))
				.contains("genre:romance", "genre:school")
				.doesNotContain("genre:fantasy", "genre:action", "genre:mystery");
	}

	/** R13.1 — 공식 작품은 추천 섹션에 있고, {@code authorType} 이 함께 온다. */
	@Test
	void R13_1_the_official_story_is_in_recommended_with_its_author_type() throws Exception {
		JsonNode recommended = sectionOf(library(), "recommended");

		assertThat(recommended.path("sectionTitle").asString()).isNotBlank();
		assertThat(storyIds(recommended)).contains(SEED_STORY.toString());
		assertThat(recommended.path("stories").get(0).path("authorType").asString()).isEqualTo("official");
		assertThat(recommended.path("stories").get(0).path("genres")).isNotEmpty();
	}

	/**
	 * <b>R13.2 — 진행률을 백분율로 주지 않는다.</b>
	 *
	 * <p>AI 생성이라 챕터당 턴 수가 가변이므로 백분율에 근거가 없다. 몇 장 중 몇 장인지만 준다.
	 */
	@Test
	void R13_2_a_started_session_appears_with_chapters_not_a_percentage() throws Exception {
		startSession();

		JsonNode continueSessions = library().path("continueSessions");

		assertThat(continueSessions).hasSize(1);
		JsonNode card = continueSessions.get(0);
		assertThat(card.path("storyId").asString()).isEqualTo(SEED_STORY.toString());
		assertThat(card.path("chapterNo").asInt()).isEqualTo(1);
		assertThat(card.path("totalChapters").asInt()).isEqualTo(6);
		assertThat(card.path("chapterTitle").asString()).isNotBlank();
		assertThat(card.has("progressPercent")).as("R13.2 — 백분율을 만들지 않는다").isFalse();
	}

	/**
	 * <b>이어하기는 자기 것만이다</b> (I-3).
	 *
	 * <p>다른 회원의 토큰으로 부르면 그 세션이 보이지 않는다 — 조회가 {@code playerRef} 로만
	 * 이루어지므로 섞일 경로가 없다.
	 */
	@Test
	void I3_another_member_does_not_see_that_session() throws Exception {
		startSession();

		MvcResult result = this.mockMvc.perform(get("/api/v1/library").with(asPlayer(UUID.randomUUID())))
				.andReturn();

		assertThat(JSON.readTree(result.getResponse().getContentAsString()).path("continueSessions"))
				.isEmpty();
	}

	/** §13.2 — 섹션 단위 조회. 더 보기와 재시도가 같은 경로다. */
	@Test
	void S13_2_a_section_can_be_fetched_on_its_own() throws Exception {
		MvcResult result = this.mockMvc
				.perform(get("/api/v1/library/sections/{key}", "genre:school").with(asPlayer()))
				.andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		JsonNode section = JSON.readTree(result.getResponse().getContentAsString());
		assertThat(section.path("sectionKey").asString()).isEqualTo("genre:school");
		assertThat(storyIds(section)).contains(SEED_STORY.toString());
	}

	/**
	 * <b>R11.1 — 고지 문구가 이 응답에 실려 온다</b> (#257).
	 *
	 * <p>Footer 가 문구를 상시 표시한다. 여기서 주지 않으면 클라이언트가 화면마다
	 * {@code /landing} 을 한 번 더 부르고, 두 응답의 캐시 수명이 갈리면 <b>같은 화면에서 다른
	 * 문구</b>가 보인다.
	 */
	@Test
	void R11_1_the_library_carries_the_notice_text() throws Exception {
		assertThat(library().path("noticeText").asString()).isEqualTo(NOTICE);
	}

	/**
	 * <b>문구가 없으면 화면을 내보내지 않는다</b> (§11, R11.1) — 랜딩과 같은 판단이다.
	 *
	 * <p>빈 문자열로 흡수하면 <b>고지가 없는 상태가 정상으로 보인다.</b>
	 */
	@Test
	void R11_1_the_library_fails_when_the_notice_is_not_configured() throws Exception {
		this.configs.deleteById(NOTICE_KEY);

		this.mockMvc.perform(get("/api/v1/library").with(asPlayer()))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(500));
	}

	/** 모르는 섹션 키는 <b>빈 섹션이 아니라 404</b> 다 — 오타가 "작품 없음"으로 보이면 안 된다. */
	@Test
	void S13_2_an_unknown_section_key_is_not_found() throws Exception {
		this.mockMvc.perform(get("/api/v1/library/sections/{key}", "popular").with(asPlayer()))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(404));
	}

	private JsonNode library() throws Exception {
		MvcResult result = this.mockMvc.perform(get("/api/v1/library").with(asPlayer())).andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		return JSON.readTree(result.getResponse().getContentAsString());
	}

	private void startSession() throws Exception {
		this.mockMvc.perform(post("/api/v1/stories/{storyId}/sessions", SEED_STORY).with(asPlayer()))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(201));
	}

	private static java.util.List<String> sectionKeys(JsonNode library) {
		return library.path("sections").valueStream().map(node -> node.path("sectionKey").asString()).toList();
	}

	private static JsonNode sectionOf(JsonNode library, String key) {
		return library.path("sections").valueStream()
				.filter(node -> key.equals(node.path("sectionKey").asString()))
				.findFirst()
				.orElseThrow(() -> new AssertionError("섹션이 없다: " + key));
	}

	private static java.util.List<String> storyIds(JsonNode section) {
		return section.path("stories").valueStream().map(node -> node.path("storyId").asString()).toList();
	}
}

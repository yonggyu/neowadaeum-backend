package com.neowadaeum.play.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.catalog.domain.ServiceConfig;
import com.neowadaeum.catalog.repository.ServiceConfigRepository;
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
 * B-37 — 랜딩 (§13.10).
 *
 * <p>확인하는 것 셋 — <b>토큰 없이 열린다</b>, <b>고지 문구가 설정에서 온다</b>(R11.1),
 * <b>{@code isLoggedIn} 을 주지 않는다</b>.
 */
class LandingApiIntegrationTests extends ContainerTestBase {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final UUID SEED_STORY = UUID.fromString("11111111-1111-4111-8111-000000000001");

	private static final String NOTICE = "이 이야기는 AI가 생성합니다.";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ServiceConfigRepository configs;

	@BeforeEach
	void configureNotice() {
		this.configs.save(ServiceConfig.of("ai.notice",
				"{\"version\":\"2026-07-21\",\"text\":\"%s\"}".formatted(NOTICE),
				Instant.parse("2026-08-27T00:00:00Z")));
	}

	@AfterEach
	void clear() {
		// 이 클래스가 심은 키만 지운다 — 표 전체를 비우면 다른 테스트가 원인 없이 깨진다 (#272).
		this.configs.deleteById("ai.notice");
	}

	/** <b>인증 없이 열린다</b> — 로그인 전 화면이다 (계약의 {@code security: []}). */
	@Test
	void S13_10_the_landing_is_reachable_without_a_token() throws Exception {
		MvcResult result = this.mockMvc.perform(get("/api/v1/landing")).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
	}

	/** <b>R11.1 — 문구가 설정에서 온다.</b> 코드에 없다. */
	@Test
	void R11_1_the_notice_text_comes_from_configuration() throws Exception {
		assertThat(landing().path("noticeText").asString()).isEqualTo(NOTICE);
	}

	/** 설정을 바꾸면 응답이 바뀐다 — 그것이 "배포 없이 갱신"의 실질이다. */
	@Test
	void R11_1_changing_the_configuration_changes_the_response() throws Exception {
		ServiceConfig config = this.configs.findById("ai.notice").orElseThrow();
		config.update("{\"version\":\"2026-08-01\",\"text\":\"새 고지 문구\"}",
				Instant.parse("2026-08-27T01:00:00Z"));
		this.configs.saveAndFlush(config);

		assertThat(landing().path("noticeText").asString()).isEqualTo("새 고지 문구");
	}

	/**
	 * <b>문구가 없으면 화면을 내보내지 않는다</b> (§11, R11.1).
	 *
	 * <p>빈 문자열을 주면 <b>고지가 없는 상태가 정상으로 보인다.</b> §11 은 사전 고지를 의무로
	 * 규정하고 R11.1 은 하드코딩을 금지한다 — 둘을 함께 지키는 방법은 이것뿐이다.
	 */
	@Test
	void S11_the_landing_fails_when_the_notice_is_not_configured() throws Exception {
		this.configs.deleteById("ai.notice");

		MvcResult result = this.mockMvc.perform(get("/api/v1/landing")).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(500);
		assertThat(JSON.readTree(result.getResponse().getContentAsString()).path("error").asString())
				.isEqualTo("INTERNAL_ERROR");
	}

	/** <b>§13.10 — {@code isLoggedIn} 을 주지 않는다.</b> 클라이언트가 토큰 보유로 판단한다. */
	@Test
	void S13_10_the_response_does_not_carry_is_logged_in() throws Exception {
		MvcResult result = this.mockMvc.perform(get("/api/v1/landing")).andReturn();

		assertThat(result.getResponse().getContentAsString()).doesNotContain("isLoggedIn");
	}

	/** 추천 작품이 라이브러리와 같은 노출 조건을 지킨다 (R2.3, I-8). */
	@Test
	void I8_featured_stories_use_the_same_visibility_rule() throws Exception {
		JsonNode featured = landing().path("featuredStories");

		assertThat(featured).isNotEmpty();
		assertThat(featured.valueStream().map(story -> story.path("storyId").asString()).toList())
				.contains(SEED_STORY.toString());
		assertThat(featured.get(0).path("title").asString()).isNotBlank();
	}

	private JsonNode landing() throws Exception {
		MvcResult result = this.mockMvc.perform(get("/api/v1/landing")).andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		return JSON.readTree(result.getResponse().getContentAsString());
	}
}

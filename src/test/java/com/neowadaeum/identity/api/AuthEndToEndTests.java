package com.neowadaeum.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.play.repository.GameStateSnapshotRepository;
import com.neowadaeum.play.repository.PlaySessionRepository;
import com.neowadaeum.play.repository.TurnRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * B-12 DoD — <b>토큰 → 보호 API 접근</b>이 HTTP 로 성립한다 (#132, #34).
 *
 * <p><b>구글 왕복은 여기에 없다.</b> 실제 구글을 부르지 않는다는 규칙(테스트 규칙)과 컨텍스트를
 * 한 벌로 유지한다는 규칙(ContainerTestBase)이 함께 걸린다 — ID 토큰 검증은
 * {@code GoogleIdTokenVerifierTests} 가 고정 응답 서버로, 회원 생성 순서는
 * {@code OAuthLoginServiceTests} 가 각각 확인한다. <b>여기서 보는 것은 그 뒤부터다.</b>
 */
class AuthEndToEndTests extends ContainerTestBase {

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

	/** 토큰이 있으면 보호 API 가 열린다. 없으면 401 이다 — 같은 요청, 헤더 하나 차이다. */
	@Test
	void B12_a_token_is_the_only_difference_between_401_and_201() throws Exception {
		this.mockMvc.perform(post("/api/v1/stories/{storyId}/sessions", SEED_STORY))
				.andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));

		this.mockMvc.perform(post("/api/v1/stories/{storyId}/sessions", SEED_STORY).with(asPlayer()))
				.andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(201));
	}

	/**
	 * <b>재발급 경로는 인증 없이 열려 있다</b> — 계약의 {@code security: []} 다.
	 *
	 * <p>닫혀 있으면 액세스 토큰이 만료된 뒤 재발급받을 방법이 없다.
	 */
	@Test
	void S13_1_refresh_is_reachable_without_authentication() throws Exception {
		this.mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"refreshToken\":\"not-a-token\"}"))
				.andExpect(r -> assertThat(r.getResponse().getStatus())
						.as("401 이면 경로에 닿은 것이다. 403 이면 체인이 막은 것이다")
						.isEqualTo(401));
	}

	/** 로그인 경로도 마찬가지다. 닫혀 있으면 아무도 토큰을 받을 수 없다. */
	@Test
	void S13_1_login_is_reachable_without_authentication() throws Exception {
		this.mockMvc.perform(post("/api/v1/auth/oauth/kakao")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"idToken\":\"x\"}"))
				.andExpect(r -> assertThat(r.getResponse().getStatus())
						.as("400 이면 컨트롤러까지 닿은 것이다")
						.isEqualTo(400));
	}

	/** 본문이 비면 400 이다 — 401 이 아니다. 검증이 인증보다 뒤에 오지 않는다. */
	@Test
	void S13_1_a_blank_refresh_token_is_a_validation_error() throws Exception {
		MvcResult result = this.mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"refreshToken\":\"\"}"))
				.andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(400);
		assertThat(JSON.readTree(result.getResponse().getContentAsString()).path("error").asString())
				.isEqualTo("VALIDATION_ERROR");
	}

	/** 에러 응답도 §9.1 형태 하나다 — 보안 필터가 낸 것도 예외가 아니다 (S-6). */
	@Test
	void S9_1_a_401_from_the_security_filter_uses_the_common_envelope() throws Exception {
		MvcResult result = this.mockMvc.perform(post("/api/v1/stories/{storyId}/sessions", SEED_STORY))
				.andReturn();

		String body = result.getResponse().getContentAsString();
		assertThat(JSON.readTree(body).path("error").asString()).isEqualTo("UNAUTHENTICATED");
		assertThat(JSON.readTree(body).has("details")).isTrue();
		assertThat(body).doesNotContain("com.neowadaeum", "Exception", "at ");
	}
}

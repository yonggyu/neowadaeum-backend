package com.neowadaeum.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.identity.auth.AuthTokenService;
import com.neowadaeum.identity.domain.User;
import com.neowadaeum.identity.repository.UserRepository;
import com.neowadaeum.play.repository.GameStateSnapshotRepository;
import com.neowadaeum.play.repository.PlaySessionRepository;
import com.neowadaeum.play.repository.TurnRepository;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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

	/** {@code RefreshTokenCookie.NAME} 과 같아야 한다 — 여기서 굳이 다시 적는 것이 계약이다. */
	private static final String REFRESH_COOKIE = "nwd_rt";

	/** Spring Security 의 기본 double-submit 짝 (ADR-0008). */
	private static final String CSRF_COOKIE = "XSRF-TOKEN";

	private static final String CSRF_HEADER = "X-XSRF-TOKEN";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PlaySessionRepository sessions;

	@Autowired
	private TurnRepository turns;

	@Autowired
	private GameStateSnapshotRepository snapshots;

	@Autowired
	private UserRepository users;

	@Autowired
	private AuthTokenService authTokenService;

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
	 *
	 * <p><b>CSRF 토큰은 실어야 한다</b> (ADR-0008). 그것이 없으면 403 이고, 그 403 은 인증이
	 * 아니라 위조 방지가 낸 것이다 — 둘을 섞지 않기 위해 여기서는 토큰을 싣는다.
	 */
	@Test
	void S13_1_refresh_is_reachable_without_authentication() throws Exception {
		this.mockMvc.perform(withCsrf(post("/api/v1/auth/refresh"))
						.cookie(new Cookie(REFRESH_COOKIE, "not-a-token")))
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

	/**
	 * ADR-0008 — <b>쿠키 하나로 로그인이 이어진다.</b>
	 *
	 * <p>이 테스트가 #278 이 물었던 것을 그대로 묻는다: 새로고침 뒤 브라우저에 남아 있는 것이
	 * 쿠키뿐일 때 <b>액세스 토큰을 다시 받을 수 있는가.</b>
	 */
	@Test
	void Issue278_a_cookie_is_the_whole_credential_for_a_refresh() throws Exception {
		String refreshToken = givenActiveMemberRefreshToken();

		MvcResult result = this.mockMvc.perform(withCsrf(post("/api/v1/auth/refresh"))
						.cookie(new Cookie(REFRESH_COOKIE, refreshToken)))
				.andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(JSON.readTree(result.getResponse().getContentAsString()).path("accessToken")
				.asString()).isNotBlank();
		assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
				.as("재발급도 새 쿠키를 굽는다")
				.contains(REFRESH_COOKIE + "=")
				.contains("HttpOnly")
				.contains("Path=/api/v1/auth/refresh")
				.contains("SameSite=Strict");
	}

	/**
	 * ADR-0008 — <b>본문은 자격 증명이 아니다.</b>
	 *
	 * <p>옛 계약({@code RefreshRequest})대로 보내면 통하지 않는다. 통하면 {@code HttpOnly} 가
	 * 주는 보장이 문장으로만 남는다.
	 */
	@Test
	void Issue278_the_old_body_shaped_request_no_longer_authenticates() throws Exception {
		String refreshToken = givenActiveMemberRefreshToken();

		this.mockMvc.perform(withCsrf(post("/api/v1/auth/refresh"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"refreshToken\":\"" + refreshToken + "\"}"))
				.andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
	}

	/**
	 * ADR-0008 — <b>이 경로는 CSRF 토큰을 요구한다.</b>
	 *
	 * <p>브라우저가 자동으로 붙이는 자격 증명이 생겼으므로 위조할 요청이 성립한다. 유효한
	 * 리프레시 쿠키를 들고 있어도 CSRF 토큰이 없으면 403 이다 — <b>401 이 아니다.</b>
	 */
	@Test
	void Issue278_a_refresh_without_a_csrf_token_is_refused() throws Exception {
		String refreshToken = givenActiveMemberRefreshToken();

		MvcResult result = this.mockMvc.perform(post("/api/v1/auth/refresh")
						.cookie(new Cookie(REFRESH_COOKIE, refreshToken)))
				.andReturn();

		assertThat(result.getResponse().getStatus())
				.as("CSRF 필터가 인가보다 먼저 돈다 — 자격 증명이 유효해도 여기서 멈춘다")
				.isEqualTo(403);
		assertThat(JSON.readTree(result.getResponse().getContentAsString()).path("error").asString())
				.isEqualTo("FORBIDDEN");
	}

	/**
	 * ADR-0008 — <b>나머지 {@code /api/v1/**} 은 면제 그대로다.</b>
	 *
	 * <p>좁힌다는 것은 한 경로를 빼는 것이지 전부를 켜는 것이 아니다. 여기서 켜지면 토큰 없는
	 * 요청이 401 이 아니라 403 이 되고, 계약(§13.1)이 약속한 {@code UNAUTHENTICATED} 가 나가지
	 * 않는다.
	 */
	@Test
	void Issue278_the_rest_of_the_api_stays_csrf_exempt() throws Exception {
		this.mockMvc.perform(post("/api/v1/stories/{storyId}/sessions", SEED_STORY).with(asPlayer()))
				.andExpect(r -> assertThat(r.getResponse().getStatus())
						.as("CSRF 토큰 없이도 통해야 한다")
						.isEqualTo(201));

		this.mockMvc.perform(post("/api/v1/stories/{storyId}/sessions", SEED_STORY))
				.andExpect(r -> assertThat(r.getResponse().getStatus())
						.as("토큰이 없으면 403 이 아니라 401 이다")
						.isEqualTo(401));
	}

	/**
	 * ADR-0008 — <b>프론트가 CSRF 토큰을 받을 자리가 항상 있다.</b>
	 *
	 * <p>지연 로딩을 끄지 않으면 <b>CSRF 를 요구하지 않는 요청에서는 토큰이 만들어지지 않고</b>,
	 * 그러면 첫 재발급이 반드시 403 이 된다. 쿠키는 <b>{@code HttpOnly} 가 아니어야</b> 한다 —
	 * 읽히는 것이 목적이기 때문이다.
	 */
	@Test
	void Issue278_every_api_response_carries_a_readable_csrf_cookie() throws Exception {
		Cookie csrf = this.mockMvc.perform(get("/api/v1/landing"))
				.andReturn().getResponse().getCookie(CSRF_COOKIE);

		assertThat(csrf).as("이 쿠키가 없으면 프론트가 보낼 토큰을 얻을 방법이 없다").isNotNull();
		assertThat(csrf.isHttpOnly()).as("읽혀야 하는 쿠키다").isFalse();
		assertThat(csrf.getValue()).isNotBlank();
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

	/** 실제 회원 하나를 만들고 그 회원의 리프레시 토큰을 돌려준다. */
	private String givenActiveMemberRefreshToken() {
		UUID playerRef = UUID.randomUUID();
		this.users.saveAndFlush(User.register(playerRef, LocalDate.of(2000, 1, 1), Instant.now()));
		return this.authTokenService.issue(playerRef).refreshToken();
	}

	/**
	 * double-submit 을 <b>실제 값으로</b> 만족시킨다.
	 *
	 * <p>테스트 헬퍼({@code SecurityMockMvcRequestPostProcessors.csrf()})를 쓰지 않는 이유는,
	 * 그것이 저장소를 갈아 끼우므로 <b>쿠키와 헤더가 같아야 한다는 사실 자체는 검증되지
	 * 않기</b> 때문이다. 여기서는 프론트가 하는 일을 그대로 한다.
	 */
	private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) {
		String token = UUID.randomUUID().toString();
		return request.cookie(new Cookie(CSRF_COOKIE, token)).header(CSRF_HEADER, token);
	}
}

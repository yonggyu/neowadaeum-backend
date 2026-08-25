package com.neowadaeum.play.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.play.repository.GameStateSnapshotRepository;
import com.neowadaeum.play.repository.PlaySessionRepository;
import com.neowadaeum.play.repository.TurnRepository;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * S-10 (B-47, #70) — dev 콘솔이 <b>브라우저 방식 그대로</b> 동작한다.
 *
 * <p>MockMvc 의 {@code with(csrf())} 는 토큰을 요청 속성으로 심어 주므로 <b>실브라우저의 CSRF
 * 경로를 지나지 않는다.</b> 여기서는 콘솔 JS 가 하는 것을 그대로 한다 — 첫 GET 이 내려준
 * {@code XSRF-TOKEN} 쿠키의 원본 값을 {@code X-XSRF-TOKEN} 헤더로 되돌린다. 이 경로가 막혀 있으면
 * 콘솔은 첫 POST 부터 403 이고, S-10 이전에는 이 경로를 쓰는 클라이언트가 없어 안 잡혔다.
 *
 * <p>{@code prod} · 무프로파일 차단은 {@code DevPlayConsoleProfileTests} 가 검증한다.
 */
class DevPlayConsoleIntegrationTests extends ContainerTestBase {

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

	/** {@code dev} 에서 콘솔이 서빙되고, 첫 GET 이 CSRF 쿠키를 발급한다. */
	@Test
	void B47_dev_serves_the_console_and_issues_the_csrf_cookie() throws Exception {
		MvcResult result = this.mockMvc.perform(get("/dev/console"))
				.andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200))
				.andReturn();

		assertThat(result.getResponse().getContentType()).contains("text/html");
		assertThat(result.getResponse().getContentAsString()).contains("dev 플레이 콘솔");

		// 지연 로딩을 풀지 않으면 쿠키가 안 내려가고, 콘솔의 첫 POST 는 보낼 토큰이 없다.
		Cookie xsrf = result.getResponse().getCookie("XSRF-TOKEN");
		assertThat(xsrf).as("첫 GET 에서 XSRF-TOKEN 쿠키가 발급돼야 한다").isNotNull();
		assertThat(xsrf.getValue()).isNotBlank();
	}

	/**
	 * <b>쿠키의 원본 토큰을 헤더로 되돌리면 통과한다</b> — 콘솔 JS 의 실제 경로다.
	 *
	 * <p>기본 {@code Xor} 핸들러는 헤더 값을 마스킹된 토큰으로 해석해 이 경로를 403 으로 만든다.
	 * {@code SpaCsrfTokenRequestHandler} 가 고치는 지점이 정확히 여기다.
	 */
	@Test
	void B47_the_raw_cookie_token_echoed_in_the_header_passes_csrf() throws Exception {
		MvcResult console = this.mockMvc.perform(get("/dev/console")).andReturn();
		Cookie xsrf = console.getResponse().getCookie("XSRF-TOKEN");

		this.mockMvc.perform(post("/api/v1/stories/{storyId}/sessions", SEED_STORY)
						.cookie(xsrf)
						.header("X-XSRF-TOKEN", xsrf.getValue()))
				.andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(201));
	}

	/** 토큰 없는 POST 는 여전히 403 이다 — SPA 핸들러가 S-9-2 의 방어를 무르지 않는다. */
	@Test
	void S9_2_a_post_without_a_token_is_still_rejected_with_the_spa_handler() throws Exception {
		this.mockMvc.perform(post("/api/v1/stories/{storyId}/sessions", SEED_STORY))
				.andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(403));

		assertThat(this.sessions.count()).as("거절된 요청이 세션을 만들면 안 된다").isZero();
	}
}

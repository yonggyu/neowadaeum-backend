package com.neowadaeum.play.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.play.repository.GameStateSnapshotRepository;
import com.neowadaeum.play.repository.PlaySessionRepository;
import com.neowadaeum.play.repository.TurnRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * S-10 (B-47, #70) — dev 콘솔이 <b>브라우저 방식 그대로</b> 동작한다.
 *
 * <p><b>B-12 가 이 파일의 전제를 바꿨다.</b> 이전에는 인증이 우회된 상태였고 콘솔은 CSRF 쿠키
 * 왕복으로 요청을 증명했다. 이제 자격 증명이 {@code Authorization} 헤더로만 오므로 브라우저가
 * 자동으로 실어 보내는 것이 없고, <b>콘솔도 다른 클라이언트와 똑같이 토큰을 들고 보낸다.</b>
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

	/** {@code dev} 에서 콘솔이 서빙된다. */
	@Test
	void B47_dev_serves_the_console() throws Exception {
		MvcResult result = this.mockMvc.perform(get("/dev/console"))
				.andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200))
				.andReturn();

		assertThat(result.getResponse().getContentType()).contains("text/html");
		assertThat(result.getResponse().getContentAsString()).contains("dev 플레이 콘솔");
	}

	/**
	 * <b>콘솔에 토큰을 넣을 자리가 있다</b> (#34).
	 *
	 * <p>우회를 제거하면 콘솔은 토큰 없이 아무것도 하지 못한다. 자리가 없으면 그 사실이
	 * 브라우저를 열어 본 사람에게만 드러나고, 대개 <b>우회를 되살리는</b> 것으로 해결된다.
	 */
	@Test
	void S34_the_console_carries_an_access_token_field() throws Exception {
		String page = this.mockMvc.perform(get("/dev/console")).andReturn()
				.getResponse().getContentAsString();

		assertThat(page).contains("id=\"accessToken\"").contains("Bearer");
	}

	/** 콘솔이 하는 것과 같은 요청 — 토큰을 헤더로 실으면 통과한다. */
	@Test
	void B47_a_bearer_token_from_the_console_starts_a_session() throws Exception {
		this.mockMvc.perform(post("/api/v1/stories/{storyId}/sessions", SEED_STORY).with(asPlayer()))
				.andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(201));
	}

	/** 토큰 없는 POST 는 401 이다. 콘솔이 dev 에 있다고 해서 경로가 열리지 않는다. */
	@Test
	void S34_a_post_without_a_token_is_rejected_even_in_dev() throws Exception {
		this.mockMvc.perform(post("/api/v1/stories/{storyId}/sessions", SEED_STORY))
				.andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));

		assertThat(this.sessions.count()).as("거절된 요청이 세션을 만들면 안 된다").isZero();
	}
}

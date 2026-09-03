package com.neowadaeum.common.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neowadaeum.ContainerTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * #248 — <b>브라우저가 실제 요청 경로에서 이 API 를 부를 수 있다.</b>
 *
 * <p>프론트는 별도 레포이고 (§8.2) 브라우저에서 보면 다른 오리진이다. 여기서 지키는 것은
 * <b>preflight 가 인가보다 먼저 판정된다</b>는 것 하나다 — 순서가 뒤집히면 토큰 없는
 * {@code OPTIONS} 가 401 이 되고, 브라우저는 그것을 <b>CORS 오류로 보고한다.</b> 원인이
 * 인증이라는 사실이 어디에도 드러나지 않는다.
 *
 * <p><b>S-11 — 허용 오리진은 테스트 전용 로컬 주소다</b> ({@code TestcontainersConfiguration}).
 */
class CorsIntegrationTests extends ContainerTestBase {

	private static final String ALLOWED = "http://localhost:5173";

	private static final String OTHER = "https://not-allowed.example.test";

	@Autowired
	private MockMvc mvc;

	/**
	 * <b>preflight 에는 토큰이 없다.</b>
	 *
	 * <p>브라우저는 {@code OPTIONS} 에 {@code Authorization} 을 싣지 않는다. 인증이 필요한
	 * 경로라도 preflight 자체는 <b>200 이어야 한다</b> — 여기서 401 이 나가면 프론트는 로그인한
	 * 상태에서도 아무 요청을 보낼 수 없다.
	 */
	@Test
	void S248_a_preflight_carries_no_token_and_still_passes() throws Exception {
		this.mvc.perform(options("/api/v1/me")
						.header("Origin", ALLOWED)
						.header("Access-Control-Request-Method", "GET"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", ALLOWED));
	}

	/** <b>허용하지 않은 오리진은 거부된다.</b> 목록에 없으면 없는 것이다. */
	@Test
	void I8_an_unlisted_origin_is_refused() throws Exception {
		this.mvc.perform(options("/api/v1/me")
						.header("Origin", OTHER)
						.header("Access-Control-Request-Method", "GET"))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
	}

	/**
	 * <b>서버가 읽는 헤더가 허용 목록에 있다.</b>
	 *
	 * <p>{@code Idempotency-Key} 가 빠지면 브라우저가 턴 요청을 <b>보내지도 못한다</b> — 그리고
	 * 그 요청은 중복 과금을 막는 값을 실은 요청이다 (R6.2).
	 */
	@Test
	void R6_2_the_headers_the_server_reads_are_allowed() throws Exception {
		this.mvc.perform(options("/api/v1/sessions/00000000-0000-4000-8000-000000000001/turns")
						.header("Origin", ALLOWED)
						.header("Access-Control-Request-Method", "POST")
						.header("Access-Control-Request-Headers", "Authorization, Idempotency-Key"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", ALLOWED));
	}

	/**
	 * <b>추적 ID 를 브라우저 스크립트가 읽을 수 있다.</b>
	 *
	 * <p>노출하지 않으면 프론트가 {@code X-Request-Id} 를 화면에 띄울 수 없고, 사용자가 오류를
	 * 제보할 때 <b>서버 로그와 이어 붙일 값이 사라진다</b> ({@link RequestIdFilter}).
	 */
	@Test
	void S248_the_request_id_is_exposed_to_the_browser() throws Exception {
		this.mvc.perform(get("/api/v1/landing").header("Origin", ALLOWED))
				.andExpect(header().string("Access-Control-Allow-Origin", ALLOWED))
				.andExpect(header().string("Access-Control-Expose-Headers",
						RequestIdFilter.HEADER_NAME));
	}

	/**
	 * <b>자격 증명을 허용한다</b> (ADR-0008, #278).
	 *
	 * <p>리프레시 토큰이 쿠키로 옮겨오면서 켰다. 브라우저는 자격 증명을 실은 교차 오리진
	 * 요청의 응답을 <b>이 헤더 없이는 버리므로</b>, 끄면 {@code Set-Cookie} 도 무시되고 로그인
	 * 유지가 성립하지 않는다.
	 *
	 * <p>켤 수 있는 근거는 허용 목록이 <b>정확 일치이고 와일드카드를 거부한다</b>는 것 하나이며,
	 * 그것은 {@code CorsPropertiesTests} 가 지킨다.
	 */
	@Test
	void Issue278_credentials_are_allowed_because_the_refresh_token_is_a_cookie() throws Exception {
		this.mvc.perform(options("/api/v1/me")
						.header("Origin", ALLOWED)
						.header("Access-Control-Request-Method", "GET"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Credentials", "true"));
	}

	/**
	 * <b>CSRF 토큰 헤더가 허용 목록에 있다</b> (ADR-0008).
	 *
	 * <p>빠지면 재발급 요청이 <b>preflight 에서 막히고</b>, 브라우저가 보고하는 것은 CSRF 가
	 * 아니라 CORS 오류다 — 원인이 어디에도 드러나지 않는다.
	 */
	@Test
	void Issue278_the_csrf_header_is_allowed_on_the_refresh_path() throws Exception {
		this.mvc.perform(options("/api/v1/auth/refresh")
						.header("Origin", ALLOWED)
						.header("Access-Control-Request-Method", "POST")
						.header("Access-Control-Request-Headers", "X-XSRF-TOKEN"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", ALLOWED));
	}

	/**
	 * <b>{@code dev} 전용 경로는 CORS 대상이 아니다.</b>
	 *
	 * <p>dev 콘솔(B-47)과 계약 서빙은 같은 오리진에서 열린다. 교차 오리진으로 열어 두면
	 * <b>dev 에서만 존재하는 경로를 다른 사이트가 부를 수 있게 된다.</b>
	 */
	@Test
	void B47_dev_only_paths_are_outside_the_cors_policy() throws Exception {
		this.mvc.perform(get("/dev/console").header("Origin", ALLOWED))
				.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
	}
}

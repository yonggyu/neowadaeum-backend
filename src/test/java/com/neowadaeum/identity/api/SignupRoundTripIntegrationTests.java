package com.neowadaeum.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.identity.auth.FakeGoogle;
import com.neowadaeum.identity.auth.SocialAccountRegistrar;
import com.neowadaeum.identity.domain.OauthProvider;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * §13-88 (이슈 #429, #431) — <b>최초 가입이 HTTP 로 성립한다.</b>
 *
 * <p><b>왜 이 자리가 따로 필요한가.</b> 가입은 <b>같은 {@code idToken} 을 두 번 보내는
 * 흐름</b>이다 — 계정이 처음인지도 무엇이 더 필요한지도 서버에 물어야 알 수 있기 때문이다.
 * 그 왕복은 요청 <b>둘에 걸쳐서만</b> 틀릴 수 있고, 그래서 어느 한 요청만 보는 검사에는
 * 잡히지 않는다. #429 가 정확히 그 사이로 빠져나갔다: 열리는 순간 아무도 가입할 수 없는
 * 상태였는데 양쪽 CI 는 초록이었고, 드러난 것은 사람이 실제 계정으로 눌렀을 때다.
 *
 * <p>기존 검사가 각각 보는 것과 여기서 보는 것은 이렇게 갈린다.
 *
 * <ul>
 * <li>{@code OAuthLoginServiceTests} — 유스케이스의 순서. 스프링 배선·직렬화·보안 체인은 밖이다
 * <li>{@code LoginNonceIntegrationTests} — 저장소의 1회성. 그 위의 순서는 보지 않는다
 * <li>{@code GoogleIdTokenVerifierTests} — 토큰 검증. 저장소를 보지 않는다
 * <li><b>여기</b> — 그 셋을 <b>실제 요청 둘</b>로 꿰어 <b>가입이 끝나는가</b>를 본다
 * </ul>
 *
 * <p><b>구글은 부르지 않는다</b>(테스트 규칙). {@link FakeGoogle} 이 JWKS 를 대신하고 테스트가
 * 그 키로 토큰을 만든다 — {@code nonce} 는 <b>요청 본문이 아니라 ID 토큰의 클레임</b>으로만
 * 올 수 있으므로(§13-87), 서명 키를 쥐지 않고서는 이 왕복을 재현할 방법이 없다. 검증기를 목으로
 * 바꾸지 않으므로 서명·발급자·대상·만료 검증은 실물 그대로 돌고, 컨텍스트도 한 벌 그대로다
 * ({@link ContainerTestBase}).
 *
 * <p><b>다른 IP 로 잰다.</b> 인증 경로 셋이 같은 창을 쓰므로(S-8), 기본 주소로 창을 소진하면
 * 같은 컨텍스트의 다른 인증 테스트가 <b>한도에 걸린 429 를 받는다</b> — 그리고 그 실패는 이
 * 파일을 가리키지 않는다.
 */
class SignupRoundTripIntegrationTests extends ContainerTestBase {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** 문서용 주소 대역(TEST-NET-3). 실재하는 호스트를 적지 않는다 (S-11). */
	private static final String OWN_ADDRESS = "203.0.113.23";

	/** {@code RefreshTokenCookie.NAME} 과 같아야 한다 — 여기서 굳이 다시 적는 것이 계약이다. */
	private static final String REFRESH_COOKIE = "nwd_rt";

	/** 판본 문자열은 증빙의 실질이다 (R10.2). 값 자체는 여기서 판정 대상이 아니다. */
	private static final String TERMS_VERSION = "2026-01-01";

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	/** 통과하는 생년월일. 고정 값이어야 실패가 날짜에 따라 흔들리지 않는다. */
	private static final LocalDate ELIGIBLE = LocalDate.of(2000, 1, 1);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private SocialAccountRegistrar registrar;

	/**
	 * <b>#429 가 살아 있으면 여기서 죽는다</b> (§13-88).
	 *
	 * <p>1차는 {@code birthDate} 도 동의도 없이 간다 — 화면은 <b>가입인지 아닌지를 모른 채</b>
	 * 로그인을 시도하기 때문이다. 서버가 {@code CONSENT_REQUIRED} 로 무엇이 더 필요한지 답하면
	 * 화면은 그것을 채워 <b>같은 토큰으로</b> 다시 보낸다. 그 2차가 통과하지 못하면 가입은
	 * 어떤 순서로도 성립하지 않는다 — nonce 는 새 ID 토큰 안에만 들어갈 수 있으므로 화면이
	 * 우회할 수도 없다.
	 */
	@Test
	void S13_88_a_signup_completes_with_the_same_id_token_the_rejection_came_back_on() throws Exception {
		String idToken = FakeGoogle.idToken("google-subject-signup", issuedNonce());

		MvcResult rejected = login(tokenOnly(idToken));

		assertThat(rejected.getResponse().getStatus()).isEqualTo(400);
		assertThat(errorOf(rejected)).isEqualTo("CONSENT_REQUIRED");

		MvcResult granted = login(signup(idToken, ELIGIBLE));

		assertThat(granted.getResponse().getStatus())
				.as("거절은 nonce 를 태우지 않는다 — 태우면 가입이 여기서 401 로 끝난다 (§13-88)")
				.isEqualTo(200);
		String accessToken = accessTokenOf(granted);
		assertThat(accessToken).isNotBlank();

		Cookie refresh = granted.getResponse().getCookie(REFRESH_COOKIE);
		assertThat(refresh).as("가입도 재발급 쿠키를 굽는다 (ADR-0008)").isNotNull();
		assertThat(refresh.getValue()).isNotBlank();
		assertThat(refresh.isHttpOnly()).isTrue();
		assertThat(refresh.getPath()).isEqualTo("/api/v1/auth/refresh");

		this.mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
				.andExpect(result -> assertThat(result.getResponse().getStatus())
						.as("발급된 토큰으로 보호 API 가 열린다 — 계정이 실제로 만들어졌다는 뜻이다")
						.isEqualTo(200));
	}

	/**
	 * <b>연령 거절도 nonce 를 태우지 않는다</b> (§13-88, R10.2).
	 *
	 * <p>생년월일을 잘못 적어 거절당한 사람은 고쳐서 <b>같은 토큰으로</b> 다시 보낸다. 이것이
	 * {@code CONSENT_REQUIRED} 와 다른 자리인 이유는 <b>판정이 둘</b>이기 때문이다 — 하나만
	 * 소비 뒤로 옮기면 다른 하나로 같은 결함이 돌아온다.
	 */
	@Test
	void S13_88_a_signup_refused_by_the_age_gate_can_be_retried_with_the_same_id_token() throws Exception {
		String idToken = FakeGoogle.idToken("google-subject-underage", issuedNonce());

		MvcResult refused = login(signup(idToken, tooYoungToday()));

		assertThat(refused.getResponse().getStatus()).isEqualTo(403);
		assertThat(errorOf(refused)).isEqualTo("AGE_RESTRICTED");

		assertThat(login(signup(idToken, ELIGIBLE)).getResponse().getStatus())
				.as("거절은 흔적을 남기지 않는다 — 고쳐 보낸 요청이 통과해야 한다")
				.isEqualTo(200);
	}

	/**
	 * <b>기존 회원은 토큰 하나로 들어온다</b> (§4.1).
	 *
	 * <p>로그인할 때마다 동의를 다시 받으면 동의 이력이 로그인 이력이 된다. 가입 판정을 회원
	 * 조회보다 앞에 두면 <b>정상 로그인이 전부 {@code CONSENT_REQUIRED}</b> 가 되므로, 그
	 * 순서를 여기서 함께 못박는다.
	 */
	@Test
	void S4_1_a_returning_member_signs_in_with_the_id_token_alone() throws Exception {
		String subject = "google-subject-returning";
		assertThat(login(signup(FakeGoogle.idToken(subject, issuedNonce()), ELIGIBLE))
				.getResponse().getStatus()).isEqualTo(200);
		UUID signedUp = playerRefOf(subject);

		MvcResult returning = login(tokenOnly(FakeGoogle.idToken(subject, issuedNonce())));

		assertThat(returning.getResponse().getStatus()).isEqualTo(200);
		assertThat(accessTokenOf(returning)).isNotBlank();
		assertThat(playerRefOf(subject))
				.as("두 번째 로그인은 계정을 새로 만들지 않는다")
				.isEqualTo(signedUp);
	}

	/**
	 * <b>성공한 로그인만이 nonce 를 태운다</b> (§13-87, §13-88).
	 *
	 * <p>가입을 끝낸 토큰을 그대로 다시 보내면 막힌다 — 1회성이 뜻하는 바가 그것이고, 이것이
	 * §13-88 이 <b>좁히지 않은</b> 자리다.
	 */
	@Test
	void S13_87_the_id_token_that_completed_a_signup_does_not_pass_twice() throws Exception {
		String idToken = FakeGoogle.idToken("google-subject-replay", issuedNonce());
		assertThat(login(signup(idToken, ELIGIBLE)).getResponse().getStatus()).isEqualTo(200);

		MvcResult replayed = login(signup(idToken, ELIGIBLE));

		assertThat(replayed.getResponse().getStatus()).isEqualTo(401);
		assertThat(errorOf(replayed)).isEqualTo("LOGIN_NONCE_INVALID");
	}

	/**
	 * <b>nonce 없는 토큰으로는 계정이 생기지 않는다</b> (§13-87).
	 *
	 * <p>서명도 대상도 만료도 멀쩡한 토큰이다. 없는 것은 <b>이 서버가 이 시도를 위해 발급한
	 * 값</b> 하나뿐이며, 그것이 없으면 가입까지 가지 못한다.
	 */
	@Test
	void S13_87_an_id_token_without_a_nonce_never_becomes_an_account() throws Exception {
		String subject = "google-subject-no-nonce";

		MvcResult refused = login(signup(FakeGoogle.idToken(subject, null), ELIGIBLE));

		assertThat(refused.getResponse().getStatus()).isEqualTo(401);
		assertThat(errorOf(refused)).isEqualTo("LOGIN_NONCE_INVALID");
		assertThat(this.registrar.findPlayerRef(OauthProvider.GOOGLE, subject))
				.as("거절된 가입은 계정을 남기지 않는다 (R10.2)")
				.isEmpty();
	}

	/** 발급 경로를 실제로 부른다 — 발급과 소비가 다른 키를 보면 여기서 드러난다. */
	private String issuedNonce() throws Exception {
		MvcResult result = this.mockMvc.perform(fromOwnAddress(post("/api/v1/auth/nonce"))).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		return JSON.readTree(result.getResponse().getContentAsString()).path("nonce").asString();
	}

	private MvcResult login(String body) throws Exception {
		return this.mockMvc.perform(fromOwnAddress(post("/api/v1/auth/oauth/google"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body)).andReturn();
	}

	/** 화면이 <b>가입인지 모른 채</b> 보내는 1차 요청. 기존 회원의 정상 요청이기도 하다. */
	private static String tokenOnly(String idToken) {
		return "{\"idToken\":\"%s\"}".formatted(idToken);
	}

	/** {@code CONSENT_REQUIRED} 를 받은 화면이 채워 보내는 2차 요청 (§13-88). */
	private static String signup(String idToken, LocalDate birthDate) {
		return ("{\"idToken\":\"%s\",\"birthDate\":\"%s\",\"consents\":["
				+ "{\"consentType\":\"tos\",\"version\":\"%s\",\"agreed\":true},"
				+ "{\"consentType\":\"privacy\",\"version\":\"%s\",\"agreed\":true},"
				+ "{\"consentType\":\"ai_notice\",\"version\":\"%s\",\"agreed\":true}]}")
				.formatted(idToken, birthDate, TERMS_VERSION, TERMS_VERSION, TERMS_VERSION);
	}

	/**
	 * 오늘 기준으로 <b>하루 모자란</b> 생년월일 (§10.1-13).
	 *
	 * <p>고정 값으로 적으면 그 날짜가 언젠가 만 15세가 되어 테스트가 조용히 무의미해진다.
	 * KST 로 재는 것은 판정이 그렇기 때문이다.
	 */
	private static LocalDate tooYoungToday() {
		return LocalDate.now(KST).minusYears(15).plusDays(1);
	}

	private UUID playerRefOf(String subject) {
		return this.registrar.findPlayerRef(OauthProvider.GOOGLE, subject).orElseThrow();
	}

	private static String errorOf(MvcResult result) throws Exception {
		return JSON.readTree(result.getResponse().getContentAsString()).path("error").asString();
	}

	private static String accessTokenOf(MvcResult result) throws Exception {
		return JSON.readTree(result.getResponse().getContentAsString()).path("accessToken").asString();
	}

	private static MockHttpServletRequestBuilder fromOwnAddress(MockHttpServletRequestBuilder request) {
		return request.with(servletRequest -> {
			servletRequest.setRemoteAddr(OWN_ADDRESS);
			return servletRequest;
		});
	}
}

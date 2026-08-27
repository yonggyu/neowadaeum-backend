package com.neowadaeum.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.identity.auth.AdminAccessGuard;
import com.neowadaeum.identity.domain.User;
import com.neowadaeum.identity.domain.UserRole;
import com.neowadaeum.identity.repository.AdminTotpRepository;
import com.neowadaeum.identity.repository.UserRepository;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * B-40 — 관리자 2FA 가 <b>실제 요청 경로에서</b> 동작한다 (R14.6, S-4).
 *
 * <p>여기서만 확인할 수 있는 것이 있다 — 보안 체인이 이 경로를 어떻게 다루는지, 그리고
 * <b>승격 없이는 관리자 문이 열리지 않는다</b>는 것이 필터·가드·컨트롤러를 다 거친 뒤에도
 * 참인지.
 */
class AdminTotpApiIntegrationTests extends ContainerTestBase {

	private static final UUID ADMIN_PLAYER_REF = UUID.fromString("00000000-0000-4000-8000-0000000000a1");

	private static final String BASE = "/api/v1/admin/2fa";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private UserRepository users;

	@Autowired
	private AdminTotpRepository registrations;

	@Autowired
	@org.springframework.beans.factory.annotation.Qualifier("identityDataSource")
	private javax.sql.DataSource identity;

	@Autowired
	private ObjectMapper json;

	@AfterEach
	void clear() {
		this.registrations.deleteAll();
		this.users.findByPlayerRef(ADMIN_PLAYER_REF).ifPresent(this.users::delete);
	}

	/** <b>토큰이 없으면 401 이다.</b> 관리자 경로도 보안 체인의 예외가 아니다. */
	@Test
	void S4_an_anonymous_request_is_unauthenticated() throws Exception {
		this.mvc.perform(post(BASE + "/enroll")).andExpect(status().isUnauthorized());
	}

	/** <b>일반 회원은 403 이다.</b> 로그인만으로는 관리자 경로에 닿지 못한다. */
	@Test
	void S4_a_normal_user_is_forbidden() throws Exception {
		this.mvc.perform(post(BASE + "/enroll").with(asPlayer())).andExpect(status().isForbidden());
	}

	/** 아직 등록하지 않은 관리자는 <b>승격 없이</b> 등록을 시작할 수 있다 — 없으면 방법이 없다. */
	@Test
	void R14_6_an_admin_can_start_enrollment_without_a_step_up() throws Exception {
		givenAdmin();

		this.mvc.perform(post(BASE + "/enroll").with(asPlayer(ADMIN_PLAYER_REF)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.secret").isNotEmpty())
				.andExpect(jsonPath("$.otpauthUri").value(org.hamcrest.Matchers
						.startsWith("otpauth://totp/")));
	}

	/**
	 * 등록 → 확정 → 승격 → 관리자 문.
	 *
	 * <p><b>확정이 곧바로 승격을 준다.</b> 방금 코드를 맞힌 사람에게 다시 코드를 묻는 것은
	 * 아무것도 더 확인하지 못한다.
	 */
	@Test
	void R14_6_confirming_returns_a_step_up() throws Exception {
		givenAdmin();
		String secret = enroll();

		JsonNode confirmed = postCode("/confirm", currentCode(secret));

		assertThat(confirmed.get("stepUpToken").asText()).isNotBlank();
		assertThat(confirmed.get("expiresIn").asLong()).isPositive();
	}

	/** 확정한 뒤에는 검증으로 다시 승격을 얻는다. */
	@Test
	void R14_6_verifying_issues_a_fresh_step_up() throws Exception {
		givenAdmin();
		String secret = enroll();
		postCode("/confirm", currentCode(secret));

		// 같은 코드는 재사용으로 막힌다 (§13-29). 창을 넘긴 코드로 다시 통과한다.
		String next = codeAt(secret, Instant.now().plus(TotpCodes_STEP));
		JsonNode verified = postCode("/verify", next);

		assertThat(verified.get("stepUpToken").asText()).isNotBlank();
	}

	/** <b>같은 코드가 두 번 통하지 않는다</b> — 요청 경로에서도 그렇다. */
	@Test
	void R14_6_a_replayed_code_is_rejected() throws Exception {
		givenAdmin();
		String secret = enroll();
		String code = currentCode(secret);
		postCode("/confirm", code);

		this.mvc.perform(post(BASE + "/verify").with(asPlayer(ADMIN_PLAYER_REF))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"code\":\"%s\"}".formatted(code)))
				.andExpect(status().isForbidden());
	}

	/** 형식이 어긋난 코드는 검증 이전에 걸린다 (§9.1). */
	@Test
	void R14_6_a_malformed_code_is_a_validation_error() throws Exception {
		givenAdmin();
		enroll();

		this.mvc.perform(post(BASE + "/confirm").with(asPlayer(ADMIN_PLAYER_REF))
						.contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"abc\"}"))
				.andExpect(status().isBadRequest());
	}

	/**
	 * <b>재등록은 승격을 요구한다.</b>
	 *
	 * <p>이미 등록을 마친 관리자가 비밀을 갈아 치우는 것은 2FA 를 무력화하는 행위다 — 그 자체가
	 * 2FA 뒤에 있어야 한다.
	 */
	@Test
	void S4_re_enrollment_requires_a_step_up() throws Exception {
		givenAdmin();
		String secret = enroll();
		String stepUp = postCode("/confirm", currentCode(secret)).get("stepUpToken").asText();

		this.mvc.perform(post(BASE + "/enroll").with(asPlayer(ADMIN_PLAYER_REF)))
				.andExpect(status().isForbidden());
		this.mvc.perform(post(BASE + "/enroll").with(asPlayer(ADMIN_PLAYER_REF))
						.header(AdminAccessGuard.STEP_UP_HEADER, stepUp))
				.andExpect(status().isOk());
	}

	/** <b>비밀이 두 번 나가지 않는다.</b> 확정·검증 응답에는 비밀이 없다 (S-3). */
	@Test
	void S3_the_secret_is_returned_only_once() throws Exception {
		givenAdmin();
		String secret = enroll();

		JsonNode confirmed = postCode("/confirm", currentCode(secret));

		assertThat(confirmed.toString()).doesNotContain(secret);
	}

	private static final java.time.Duration TotpCodes_STEP = java.time.Duration.ofSeconds(30);

	private void givenAdmin() {
		UUID userId = this.users.save(User.register(ADMIN_PLAYER_REF, null, Instant.now())).getId();
		// 역할 승격 경로를 프로덕션 코드에 만들지 않기 위해서다 (S-11).
		new org.springframework.jdbc.core.JdbcTemplate(this.identity)
				.update("UPDATE \"user\" SET role = 'admin' WHERE id = ?", userId);
	}

	private String enroll() throws Exception {
		String body = this.mvc.perform(post(BASE + "/enroll").with(asPlayer(ADMIN_PLAYER_REF)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return this.json.readTree(body).get("secret").asText();
	}

	private JsonNode postCode(String path, String code) throws Exception {
		String body = this.mvc.perform(post(BASE + path).with(asPlayer(ADMIN_PLAYER_REF))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"code\":\"%s\"}".formatted(code)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return this.json.readTree(body);
	}

	private String currentCode(String base32Secret) {
		return codeAt(base32Secret, Instant.now());
	}

	/** 테스트가 인증기 앱의 자리에 선다 — 서버가 기대하는 코드를 밖에서 만든다. */
	private String codeAt(String base32Secret, Instant instant) {
		byte[] secret = decodeBase32(base32Secret);
		long step = Math.floorDiv(instant.getEpochSecond(), TotpCodes_STEP.toSeconds());
		byte[] counter = new byte[Long.BYTES];
		for (int i = counter.length - 1; i >= 0; i--) {
			counter[i] = (byte) (step & 0xFF);
			step >>>= Byte.SIZE;
		}
		try {
			Mac mac = Mac.getInstance("HmacSHA1");
			mac.init(new SecretKeySpec(secret, "HmacSHA1"));
			byte[] hash = mac.doFinal(counter);
			int offset = hash[hash.length - 1] & 0x0F;
			int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
					| ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
			return "%06d".formatted(binary % 1_000_000);
		}
		catch (java.security.GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static byte[] decodeBase32(String encoded) {
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		int buffer = 0;
		int bits = 0;
		for (char c : encoded.toCharArray()) {
			buffer = (buffer << 5) | "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c);
			bits += 5;
			if (bits >= Byte.SIZE) {
				bits -= Byte.SIZE;
				out.write((buffer >>> bits) & 0xFF);
			}
		}
		return out.toByteArray();
	}
}

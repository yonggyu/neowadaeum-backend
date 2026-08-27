package com.neowadaeum.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.ai.log.AccessAuditLogRepository;
import com.neowadaeum.ai.log.AiCallLog;
import com.neowadaeum.ai.log.AiCallLogRepository;
import com.neowadaeum.identity.access.AdminAccessGuard;
import com.neowadaeum.identity.domain.User;
import com.neowadaeum.identity.repository.UserRepository;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Limit;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B-41 — Debug 콘솔이 <b>실제 요청 경로에서</b> 동작한다 (§14, R12.3, S-5).
 *
 * <p>여기서만 확인할 수 있는 것이 있다 — 세 조건이 이 경로에도 서 있는지, 그리고 <b>원문을
 * 꺼내면 정말로 기록이 남는지</b>가 필터·가드·두 파사드를 다 거친 뒤에도 참인지.
 */
class AdminDebugApiIntegrationTests extends ContainerTestBase {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final UUID ADMIN_PLAYER_REF = UUID.fromString("00000000-0000-4000-8000-0000000000b1");

	private static final UUID SEED_STORY = UUID.fromString("11111111-1111-4111-8111-000000000001");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private UserRepository users;

	@Autowired
	private AiCallLogRepository aiCalls;

	@Autowired
	private AccessAuditLogRepository accessLogs;

	@Autowired
	@Qualifier("identityDataSource")
	private javax.sql.DataSource identity;

	@AfterEach
	void clear() {
		this.accessLogs.deleteAll();
		this.aiCalls.deleteAll();
		this.users.findByPlayerRef(ADMIN_PLAYER_REF).ifPresent(this.users::delete);
	}

	/** <b>승격 없이는 열리지 않는다.</b> 역할과 IP 가 맞아도 그렇다 (S-4). */
	@Test
	void S4_without_a_step_up_the_console_stays_shut() throws Exception {
		givenAdmin();

		this.mvc.perform(get(debugPath(UUID.randomUUID())).with(asPlayer(ADMIN_PLAYER_REF)))
				.andExpect(status().isForbidden());
	}

	/** 일반 회원은 승격을 얻을 방법조차 없다. */
	@Test
	void S4_a_normal_user_is_forbidden() throws Exception {
		this.mvc.perform(get(debugPath(UUID.randomUUID())).with(asPlayer()))
				.andExpect(status().isForbidden());
	}

	/** 없는 세션은 404 다 — 승격을 통과한 뒤에도 없는 것은 없다. */
	@Test
	void R14_5_an_unknown_session_is_not_found() throws Exception {
		givenAdmin();
		String stepUp = stepUpToken();

		this.mvc.perform(get(debugPath(UUID.randomUUID())).with(asPlayer(ADMIN_PLAYER_REF))
						.header(AdminAccessGuard.STEP_UP_HEADER, stepUp))
				.andExpect(status().isNotFound());
	}

	/**
	 * 세 조건을 통과하면 세션 상태와 <b>원문</b>이 함께 온다 (§14).
	 *
	 * <p><b>{@code playerRef} 는 나가지 않는다</b> (I-3) — 관리자 화면이 회원 조회 도구가 되면 안 된다.
	 */
	@Test
	void R14_5_the_console_returns_the_session_and_the_raw_calls() throws Exception {
		givenAdmin();
		UUID sessionId = givenSessionWithOneTurn();
		givenAiCall(sessionId);

		JsonNode body = JSON.readTree(this.mvc
				.perform(get(debugPath(sessionId)).with(asPlayer(ADMIN_PLAYER_REF))
						.header(AdminAccessGuard.STEP_UP_HEADER, stepUpToken()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

		assertThat(body.path("session").path("sessionId").asText()).isEqualTo(sessionId.toString());
		assertThat(body.path("session").path("providerId").asText()).isNotBlank();
		assertThat(body.path("session").path("modelId").asText()).isNotBlank();
		assertThat(body.path("aiCalls")).hasSize(1);
		assertThat(body.path("aiCalls").get(0).path("requestRaw").asText()).isEqualTo("요청 원문");
		assertThat(body.toString()).doesNotContain(ADMIN_PLAYER_REF.toString());
	}

	/** <b>원문을 읽은 사실이 남는다</b> (R12.3, S-5). 건마다 한 줄이다. */
	@Test
	void S5_reading_the_raw_text_leaves_a_record() throws Exception {
		givenAdmin();
		UUID sessionId = givenSessionWithOneTurn();
		UUID callLogId = givenAiCall(sessionId);

		this.mvc.perform(get(debugPath(sessionId)).with(asPlayer(ADMIN_PLAYER_REF))
						.header(AdminAccessGuard.STEP_UP_HEADER, stepUpToken()))
				.andExpect(status().isOk());

		assertThat(this.accessLogs.findByResourceAndResourceIdOrderByCreatedAtDesc("ai_call_log",
				callLogId, Limit.of(10))).hasSize(1);
	}

	/** 막힌 요청은 원문을 읽지 않는다 — 따라서 열람 기록도 남지 않는다. */
	@Test
	void S5_a_blocked_request_reads_nothing() throws Exception {
		givenAdmin();
		UUID sessionId = givenSessionWithOneTurn();
		givenAiCall(sessionId);

		this.mvc.perform(get(debugPath(sessionId)).with(asPlayer(ADMIN_PLAYER_REF)))
				.andExpect(status().isForbidden());

		assertThat(this.accessLogs.findAll()).isEmpty();
	}

	private static String debugPath(UUID sessionId) {
		return "/api/v1/admin/sessions/%s/debug".formatted(sessionId);
	}

	private void givenAdmin() {
		UUID userId = this.users.save(User.register(ADMIN_PLAYER_REF, null, Instant.now())).getId();
		// 역할 승격 경로를 프로덕션 코드에 만들지 않기 위해서다 (S-11).
		new org.springframework.jdbc.core.JdbcTemplate(this.identity)
				.update("UPDATE \"user\" SET role = 'admin' WHERE id = ?", userId);
	}

	/** 등록 → 확정. 확정이 곧바로 승격을 돌려준다. */
	private String stepUpToken() throws Exception {
		String enrolled = this.mvc.perform(post("/api/v1/admin/2fa/enroll").with(asPlayer(ADMIN_PLAYER_REF)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String secret = JSON.readTree(enrolled).get("secret").asText();

		String confirmed = this.mvc.perform(post("/api/v1/admin/2fa/confirm")
						.with(asPlayer(ADMIN_PLAYER_REF)).contentType(MediaType.APPLICATION_JSON)
						.content("{\"code\":\"%s\"}".formatted(codeFor(secret))))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JSON.readTree(confirmed).get("stepUpToken").asText();
	}

	private UUID givenSessionWithOneTurn() throws Exception {
		String created = this.mvc.perform(post("/api/v1/stories/%s/sessions".formatted(SEED_STORY))
						.with(asPlayer(ADMIN_PLAYER_REF)).contentType(MediaType.APPLICATION_JSON)
						.content("{\"restart\":true}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(JSON.readTree(created).get("sessionId").asText());
	}

	private UUID givenAiCall(UUID sessionId) {
		return this.aiCalls.save(AiCallLog.record(new AiCallLog.Draft(sessionId, null, "turn", "fixed",
				"scenario", null, "요청 원문", "응답 원문", 11, 22, 33, 44L, "[]", 1), Instant.now())).getId();
	}

	/** 테스트가 인증기 앱의 자리에 선다. */
	private String codeFor(String base32Secret) {
		byte[] secret = decodeBase32(base32Secret);
		long step = Math.floorDiv(Instant.now().getEpochSecond(), 30L);
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

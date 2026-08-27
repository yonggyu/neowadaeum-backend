package com.neowadaeum.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.ai.log.AdminAuditLogRepository;
import com.neowadaeum.identity.access.AdminAccessGuard;
import com.neowadaeum.identity.domain.User;
import com.neowadaeum.identity.repository.UserRepository;
import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.domain.SafetyVerdict;
import com.neowadaeum.play.domain.Turn;
import com.neowadaeum.play.repository.GameStateSnapshotRepository;
import com.neowadaeum.play.repository.PlaySessionRepository;
import com.neowadaeum.play.repository.StorySummaryRepository;
import com.neowadaeum.play.repository.TurnRepository;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * B-43 — 자유입력이 <b>실제 요청 경로에서</b> 동작한다 (§14, I-17, I-18).
 *
 * <p>여기서만 확인할 수 있는 것: 세 조건이 이 문에도 서 있는지, 사용자 소유 세션이 필터·가드·
 * 파사드를 다 거친 뒤에도 거절되는지, 그리고 <b>만들어진 턴이 자유입력으로 표시되는지</b>.
 */
class AdminFreeInputApiIntegrationTests extends ContainerTestBase {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final UUID ADMIN_PLAYER_REF = UUID.fromString("00000000-0000-4000-8000-0000000000e1");

	private static final UUID STORY_ID = UUID.fromString("11111111-1111-4111-8111-000000000001");

	private static final UUID VERSION_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private UserRepository users;

	@Autowired
	private PlaySessionRepository sessions;

	@Autowired
	private TurnRepository turns;

	@Autowired
	private GameStateSnapshotRepository snapshots;

	@Autowired
	private StorySummaryRepository summaries;

	@Autowired
	private AdminAuditLogRepository auditLogs;

	@Autowired
	@Qualifier("identityDataSource")
	private javax.sql.DataSource identity;

	@AfterEach
	void clear() {
		this.summaries.deleteAll();
		this.snapshots.deleteAll();
		this.turns.deleteAll();
		this.sessions.findAll().stream().filter(s -> ADMIN_PLAYER_REF.equals(s.getPlayerRef()))
				.forEach(this.sessions::delete);
		this.auditLogs.deleteAll();
		this.users.findByPlayerRef(ADMIN_PLAYER_REF).ifPresent(this.users::delete);
	}

	/** 승격 없이는 열리지 않는다 (S-4). */
	@Test
	void S4_without_a_step_up_free_input_is_forbidden() throws Exception {
		givenAdmin();
		UUID sessionId = givenSession(true);

		this.mvc.perform(freeInput(sessionId, "창밖을 본다", null)).andExpect(status().isForbidden());
	}

	/**
	 * <b>사용자 소유 세션에는 넣지 못한다</b> (I-18, R14.3).
	 *
	 * <p>세 조건을 전부 통과한 관리자여도 그렇다 — 이것은 권한이 아니라 <b>세션의 성질</b>이다.
	 */
	@Test
	void I18_a_user_owned_session_refuses_free_input() throws Exception {
		givenAdmin();
		UUID sessionId = givenSession(false);
		String stepUp = stepUpToken();

		this.mvc.perform(freeInput(sessionId, "창밖을 본다", stepUp)).andExpect(status().isForbidden());
		assertThat(this.turns.findBySessionIdAndDeletedAtIsNullOrderByTurnNoDesc(sessionId,
				Limit.of(10))).hasSize(1);
	}

	/** 빈 행동 문장은 검증에서 걸린다 (§9.1). */
	@Test
	void R14_1_a_blank_action_is_a_validation_error() throws Exception {
		givenAdmin();
		UUID sessionId = givenSession(true);
		String stepUp = stepUpToken();

		this.mvc.perform(freeInput(sessionId, "   ", stepUp)).andExpect(status().isBadRequest());
	}

	/** 없는 세션은 404 다. */
	@Test
	void R14_3_an_unknown_session_is_not_found() throws Exception {
		givenAdmin();
		String stepUp = stepUpToken();

		this.mvc.perform(freeInput(UUID.randomUUID(), "창밖을 본다", stepUp))
				.andExpect(status().isNotFound());
	}

	/**
	 * 테스트 세션에서는 턴이 만들어지고, <b>그 턴이 자유입력으로 표시된다</b> (R14.2).
	 *
	 * <p>이 표시가 없으면 나중에 그 턴이 <b>사람이 넣은 것</b>임을 알 수 없다.
	 */
	@Test
	void R14_2_a_free_input_turn_is_marked_as_such() throws Exception {
		UUID adminUserId = givenAdmin();
		UUID sessionId = givenSession(true);
		String stepUp = stepUpToken();

		succeed(freeInput(sessionId, "창밖을 본다", stepUp));

		assertThat(this.turns.findBySessionIdAndTurnNoAndDeletedAtIsNull(sessionId, 2)).get()
				.satisfies(turn -> {
					assertThat(turn.isAdminFreeInput()).isTrue();
					assertThat(turn.isAiGenerated()).isTrue();
				});
		assertThat(this.auditLogs.findByAdminUserIdOrderByCreatedAtDesc(adminUserId, Limit.of(10)))
				.anySatisfy(log -> assertThat(log.getAction()).isEqualTo("admin.session.freeInput"));
	}

	/** <b>행동 문장이 감사에 실리지 않는다</b> (S-3, S-11). */
	@Test
	void S3_the_action_text_never_reaches_the_audit() throws Exception {
		UUID adminUserId = givenAdmin();
		UUID sessionId = givenSession(true);
		String stepUp = stepUpToken();

		succeed(freeInput(sessionId, "창밖을 본다", stepUp));

		assertThat(this.auditLogs.findByAdminUserIdOrderByCreatedAtDesc(adminUserId, Limit.of(10)))
				.allSatisfy(log -> assertThat(log.getPayload()).doesNotContain("창밖을 본다"));
	}

	/**
	 * 200 을 기대하되, <b>아니면 응답 본문을 실패 메시지에 싣는다.</b>
	 *
	 * <p>{@code status().isOk()} 만 걸면 CI 로그에 "AssertionError" 한 줄만 남고, 컨테이너
	 * 테스트를 로컬에서 돌릴 수 없는 상황에서는 그 한 줄로 원인을 좁힐 수 없다.
	 *
	 * <p>본문에 원문이 실리지 않는다는 것은 별도 테스트가 지킨다 (S-3).
	 */
	private void succeed(
			org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder)
			throws Exception {
		var response = this.mvc.perform(builder).andReturn().getResponse();
		// 토큰은 요청 헤더에 있고 본문에는 없다 — 본문만 찍는다 (S-3).
		System.out.println("[free-input] status=" + response.getStatus() + " body="
				+ response.getContentAsString());
		assertThat(response.getStatus())
				.withFailMessage("expected 200 but was %d: %s", response.getStatus(),
						response.getContentAsString())
				.isEqualTo(200);
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder freeInput(
			UUID sessionId, String action, String stepUp) {
		var builder = post("/api/v1/admin/sessions/%s/turns/free".formatted(sessionId))
				.with(asPlayer(ADMIN_PLAYER_REF)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"action\":\"%s\"}".formatted(action));
		return (stepUp != null) ? builder.header(AdminAccessGuard.STEP_UP_HEADER, stepUp) : builder;
	}

	private UUID givenAdmin() {
		UUID userId = this.users.save(User.register(ADMIN_PLAYER_REF, null, Instant.now())).getId();
		// 역할 승격 경로를 프로덕션 코드에 만들지 않기 위해서다 (S-11).
		new org.springframework.jdbc.core.JdbcTemplate(this.identity)
				.update("UPDATE \"user\" SET role = 'admin' WHERE id = ?", userId);
		return userId;
	}

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

	/** 턴 1 까지 진행된 세션. 결정론 시나리오가 {@code (1, null)} 자리를 갖고 있다 (B-43). */
	private UUID givenSession(boolean testSession) {
		Instant now = Instant.now();
		PlaySession session = this.sessions.save(PlaySession.start(ADMIN_PLAYER_REF, STORY_ID,
				VERSION_ID, "fixed", "scenario", testSession, now));
		UUID sessionId = session.getId();
		session.recordTurn(1, 1, now);
		this.turns.save(Turn.create(new Turn.TurnDraft(sessionId, 1, 1, "[\"본문\"]", "[]", null,
				false, false, null, SafetyVerdict.PASS, true, false), now));
		this.sessions.saveAndFlush(session);
		return sessionId;
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

package com.neowadaeum.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.ai.log.AdminAuditLogRepository;
import com.neowadaeum.identity.access.AdminAccessGuard;
import com.neowadaeum.identity.domain.User;
import com.neowadaeum.identity.repository.UserRepository;
import com.neowadaeum.play.domain.GameStateSnapshot;
import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.domain.SafetyVerdict;
import com.neowadaeum.play.domain.StorySummary;
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
 * B-42 — 되돌리기가 <b>실제 요청 경로에서</b> 동작한다 (§14, R14.4, R14.5).
 *
 * <p>여기서만 확인할 수 있는 것: 세 조건이 이 문에도 서 있는지, 그리고 <b>무엇을 어디까지
 * 되돌렸는지가 감사에 남는지.</b>
 */
class AdminMaintenanceApiIntegrationTests extends ContainerTestBase {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final UUID ADMIN_PLAYER_REF = UUID.fromString("00000000-0000-4000-8000-0000000000d1");

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

	/** <b>승격 없이는 고칠 수 없다.</b> 보는 문과 고치는 문 모두에 세 조건이 서 있다 (S-4). */
	@Test
	void S4_without_a_step_up_nothing_can_be_rolled_back() throws Exception {
		givenAdmin();
		UUID sessionId = givenSessionAt(5);

		this.mvc.perform(post(rollbackPath(sessionId)).with(asPlayer(ADMIN_PLAYER_REF))
						.contentType(MediaType.APPLICATION_JSON).content("{\"toTurnNo\":3}"))
				.andExpect(status().isForbidden());

		assertThat(this.sessions.findById(sessionId)).get()
				.extracting(PlaySession::getTurnNo).isEqualTo(5);
	}

	/** 세 조건을 통과하면 되돌아가고, <b>접은 수가 응답에 온다.</b> */
	@Test
	void R14_4_a_rollback_reports_what_it_folded() throws Exception {
		givenAdmin();
		UUID sessionId = givenSessionAt(5);
		String stepUp = stepUpToken();

		this.mvc.perform(post(rollbackPath(sessionId)).with(asPlayer(ADMIN_PLAYER_REF))
						.header(AdminAccessGuard.STEP_UP_HEADER, stepUp)
						.contentType(MediaType.APPLICATION_JSON).content("{\"toTurnNo\":3}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.turnNo").value(3))
				.andExpect(jsonPath("$.foldedTurns").value(2))
				.andExpect(jsonPath("$.foldedSnapshots").value(2))
				.andExpect(jsonPath("$.foldedSummaries").value(2));
	}

	/** <b>무엇을 어디까지 되돌렸는지가 감사에 남는다</b> (R14.5). */
	@Test
	void R14_5_a_rollback_is_audited_with_what_it_folded() throws Exception {
		UUID adminUserId = givenAdmin();
		UUID sessionId = givenSessionAt(5);
		String stepUp = stepUpToken();

		this.mvc.perform(post(rollbackPath(sessionId)).with(asPlayer(ADMIN_PLAYER_REF))
						.header(AdminAccessGuard.STEP_UP_HEADER, stepUp)
						.contentType(MediaType.APPLICATION_JSON).content("{\"toTurnNo\":2}"))
				.andExpect(status().isOk());

		assertThat(this.auditLogs.findByAdminUserIdOrderByCreatedAtDesc(adminUserId, Limit.of(10)))
				.anySatisfy(log -> {
					assertThat(log.getAction()).isEqualTo("admin.session.rollback");
					assertThat(log.getTargetId()).isEqualTo(sessionId);
					assertThat(log.getPayload()).contains("foldedSummaries");
				});
	}

	/** 앞으로 되돌리기는 400 이다 — "되돌리기"가 진행이 되면 안 된다. */
	@Test
	void I6_rolling_forward_is_a_validation_error() throws Exception {
		givenAdmin();
		UUID sessionId = givenSessionAt(5);
		String stepUp = stepUpToken();

		this.mvc.perform(post(rollbackPath(sessionId)).with(asPlayer(ADMIN_PLAYER_REF))
						.header(AdminAccessGuard.STEP_UP_HEADER, stepUp)
						.contentType(MediaType.APPLICATION_JSON).content("{\"toTurnNo\":9}"))
				.andExpect(status().isBadRequest());
	}

	/** 음수는 검증에서 걸린다 (§9.1). */
	@Test
	void I6_a_negative_target_is_refused() throws Exception {
		givenAdmin();
		UUID sessionId = givenSessionAt(5);
		String stepUp = stepUpToken();

		this.mvc.perform(post(rollbackPath(sessionId)).with(asPlayer(ADMIN_PLAYER_REF))
						.header(AdminAccessGuard.STEP_UP_HEADER, stepUp)
						.contentType(MediaType.APPLICATION_JSON).content("{\"toTurnNo\":-1}"))
				.andExpect(status().isBadRequest());
	}

	/** 없는 세션은 404 다. */
	@Test
	void R14_4_an_unknown_session_is_not_found() throws Exception {
		givenAdmin();
		String stepUp = stepUpToken();

		this.mvc.perform(post(rollbackPath(UUID.randomUUID())).with(asPlayer(ADMIN_PLAYER_REF))
						.header(AdminAccessGuard.STEP_UP_HEADER, stepUp)
						.contentType(MediaType.APPLICATION_JSON).content("{\"toTurnNo\":1}"))
				.andExpect(status().isNotFound());
	}

	/**
	 * <b>선택 기록이 없는 턴은 다시 만들 수 없다.</b>
	 *
	 * <p>그 턴은 아직 진행되지 않은 것이다 — 다시 만들 대상이 아니다 (§13-30).
	 */
	@Test
	void R14_4_regenerating_a_turn_without_a_recorded_choice_is_refused() throws Exception {
		givenAdmin();
		UUID sessionId = givenSessionAt(5);
		String stepUp = stepUpToken();

		this.mvc.perform(post("/api/v1/admin/sessions/%s/turns/5/regenerate".formatted(sessionId))
						.with(asPlayer(ADMIN_PLAYER_REF))
						.header(AdminAccessGuard.STEP_UP_HEADER, stepUp))
				.andExpect(status().isBadRequest());

		// 거절이면 아무것도 접히지 않는다 — 되돌리기는 선택 확인 뒤에 일어난다.
		assertThat(this.sessions.findById(sessionId)).get()
				.extracting(PlaySession::getTurnNo).isEqualTo(5);
	}

	private static String rollbackPath(UUID sessionId) {
		return "/api/v1/admin/sessions/%s/rollback".formatted(sessionId);
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

	private UUID givenSessionAt(int turnNo) {
		Instant now = Instant.now();
		PlaySession session = this.sessions.save(PlaySession.start(ADMIN_PLAYER_REF, STORY_ID,
				VERSION_ID, "fixed", "scenario", false, now));
		UUID sessionId = session.getId();
		for (int no = 1; no <= turnNo; no++) {
			session.recordTurn(no, 1, now);
			this.turns.save(Turn.create(new Turn.TurnDraft(sessionId, no, 1, "[\"본문\"]", "[]", null,
					false, false, null, SafetyVerdict.PASS, true, false), now));
			this.snapshots.save(GameStateSnapshot.capture(sessionId, no, "{}", now));
			this.summaries.save(StorySummary.of(sessionId, no, "요약 " + no, 10, now));
		}
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

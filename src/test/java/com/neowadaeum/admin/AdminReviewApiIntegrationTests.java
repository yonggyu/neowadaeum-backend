package com.neowadaeum.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.ai.log.AdminAuditLogRepository;
import com.neowadaeum.authoring.draft.StoryDraftRepository;
import com.neowadaeum.authoring.review.StoryReviewRepository;
import com.neowadaeum.identity.access.AdminAccessGuard;
import com.neowadaeum.identity.domain.User;
import com.neowadaeum.identity.repository.UserRepository;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Limit;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * B-55 — 검수 큐가 <b>실제 요청 경로에서</b> 동작한다 (§14, R8.6, R14.5).
 *
 * <p>여기서만 확인할 수 있는 것: <b>세 조건이 보는 문에도 서 있는지</b>, 그리고 <b>누가 무엇을
 * 열었는지가 감사에 남는지.</b>
 *
 * <p><b>S-11 — 픽스처는 전부 가상의 문자열이다.</b>
 */
class AdminReviewApiIntegrationTests extends ContainerTestBase {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final UUID ADMIN_PLAYER_REF = UUID.fromString("00000000-0000-4000-8000-0000000000e2");

	private static final String PAYLOAD = "{\"title\":\"봄의 학교\",\"shortDesc\":\"소개\","
			+ "\"worldIntro\":\"소개\",\"worldPrompt\":\"봄의 학교에서 시작한다.\","
			+ "\"chapters\":[{\"title\":\"1장\",\"summarySeed\":\"시작\"}],"
			+ "\"endings\":[{\"label\":\"좋은 끝\",\"epilogueText\":\"잘 끝났다.\"}]}";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private UserRepository users;

	@Autowired
	private StoryDraftRepository drafts;

	@Autowired
	private StoryReviewRepository reviews;

	@Autowired
	private AdminAuditLogRepository auditLogs;

	@Autowired
	@Qualifier("identityDataSource")
	private DataSource identity;

	@Autowired
	@Qualifier("catalogDataSource")
	private DataSource catalog;

	@AfterEach
	void clear() {
		JdbcClient jdbc = JdbcClient.create(this.catalog);
		this.reviews.findAll().forEach(review -> {
			UUID storyId = review.getStoryId();
			jdbc.sql("DELETE FROM chapter_def WHERE story_id = ?").param(storyId).update();
			jdbc.sql("DELETE FROM ending_def WHERE story_id = ?").param(storyId).update();
			jdbc.sql("UPDATE story SET current_version_id = NULL WHERE id = ?").param(storyId).update();
			jdbc.sql("DELETE FROM story_version WHERE story_id = ?").param(storyId).update();
			jdbc.sql("DELETE FROM story WHERE id = ?").param(storyId).update();
		});
		this.reviews.deleteAll();
		this.drafts.deleteAll();
		this.auditLogs.deleteAll();
		this.users.findByPlayerRef(ADMIN_PLAYER_REF).ifPresent(this.users::delete);
	}

	/**
	 * <b>승격 없이는 큐가 보이지 않는다</b> (S-4, I-8).
	 *
	 * <p>큐에 걸린 것은 아직 아무도 보지 못한 작품이며, 그 목록이 새면 검수 전 UGC 가 새는
	 * 것과 같다.
	 */
	@Test
	void SEC4_without_a_step_up_the_queue_is_not_visible() throws Exception {
		givenAdmin();

		this.mvc.perform(get("/api/v1/admin/reviews").with(asPlayer(ADMIN_PLAYER_REF)))
				.andExpect(status().isForbidden());
	}

	/** 세 조건을 통과하면 기다리는 작품이 보인다. */
	@Test
	void R8_6_the_queue_lists_stories_waiting_for_a_human() throws Exception {
		givenAdmin();
		UUID storyId = givenPublicSubmission();
		String stepUp = stepUpToken();

		this.mvc.perform(get("/api/v1/admin/reviews").with(asPlayer(ADMIN_PLAYER_REF))
						.header(AdminAccessGuard.STEP_UP_HEADER, stepUp))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.storyId=='%s')]".formatted(storyId)).exists());
	}

	/** <b>승인이 곧 게시다</b> (R8.8) — 판정이 실제 요청 경로에서 작품을 연다. */
	@Test
	void R8_8_a_pass_approves_the_story() throws Exception {
		givenAdmin();
		UUID storyId = givenPublicSubmission();
		String stepUp = stepUpToken();

		this.mvc.perform(verdict(storyId, stepUp, "{\"verdict\":\"PASS\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reviewStatus").value("approved"));
	}

	/** <b>누가 무엇을 열었는지가 감사에 남는다</b> (R14.5). */
	@Test
	void R14_5_a_verdict_is_audited() throws Exception {
		UUID adminUserId = givenAdmin();
		UUID storyId = givenPublicSubmission();
		String stepUp = stepUpToken();

		this.mvc.perform(verdict(storyId, stepUp, "{\"verdict\":\"PASS\"}"))
				.andExpect(status().isOk());

		assertThat(this.auditLogs.findByAdminUserIdOrderByCreatedAtDesc(adminUserId, Limit.of(10)))
				.anySatisfy(log -> {
					assertThat(log.getAction()).isEqualTo("admin.review.verdict");
					assertThat(log.getTargetId()).isEqualTo(storyId);
					assertThat(log.getPayload()).contains("pass");
				});
	}

	/** 이미 처리된 검수에 다시 판정이 오면 {@code 409} 다 — 나중에 누른 쪽이 이기면 안 된다. */
	@Test
	void R8_6_a_second_verdict_on_the_same_story_is_a_conflict() throws Exception {
		givenAdmin();
		UUID storyId = givenPublicSubmission();
		String stepUp = stepUpToken();
		this.mvc.perform(verdict(storyId, stepUp, "{\"verdict\":\"PASS\"}")).andExpect(status().isOk());

		this.mvc.perform(verdict(storyId, stepUp, "{\"verdict\":\"REJECT\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("REVIEW_NOT_PENDING"));
	}

	/** 알 수 없는 판정은 검증에서 걸린다 (§9.1). */
	@Test
	void R8_7_an_unknown_verdict_is_a_validation_error() throws Exception {
		givenAdmin();
		UUID storyId = givenPublicSubmission();
		String stepUp = stepUpToken();

		this.mvc.perform(verdict(storyId, stepUp, "{\"verdict\":\"MAYBE\"}"))
				.andExpect(status().isBadRequest());
	}

	/** <b>사유는 카테고리만이다</b> (R8.7) — 자유 문자열은 받지 않는다. */
	@Test
	void R8_7_a_free_text_reason_is_refused() throws Exception {
		givenAdmin();
		UUID storyId = givenPublicSubmission();
		String stepUp = stepUpToken();

		this.mvc.perform(verdict(storyId, stepUp,
						"{\"verdict\":\"REJECT\",\"reasons\":[\"이나린 이라는 이름\"]}"))
				.andExpect(status().isBadRequest());
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder verdict(
			UUID storyId, String stepUp, String body) {
		return post("/api/v1/admin/reviews/%s/verdict".formatted(storyId))
				.with(asPlayer(ADMIN_PLAYER_REF)).header(AdminAccessGuard.STEP_UP_HEADER, stepUp)
				.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	/** 작성자가 {@code public} 으로 제출한다 — 그것이 큐에 들어오는 유일한 길이다 (R8.6). */
	private UUID givenPublicSubmission() throws Exception {
		UUID authorRef = UUID.randomUUID();
		String created = this.mvc.perform(post("/api/v1/authoring/drafts").with(asPlayer(authorRef)))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		UUID draftId = UUID.fromString(JSON.readTree(created).get("draftId").asString());

		this.mvc.perform(patch("/api/v1/authoring/drafts/%s".formatted(draftId)).with(asPlayer(authorRef))
						.contentType(MediaType.APPLICATION_JSON)
						.content(JSON.writeValueAsString(java.util.Map.of("step", 5, "payload", PAYLOAD))))
				.andExpect(status().isOk());

		String submitted = this.mvc
				.perform(post("/api/v1/authoring/drafts/%s/submit".formatted(draftId))
						.with(asPlayer(authorRef)).contentType(MediaType.APPLICATION_JSON)
						.content("{\"visibility\":\"PUBLIC\"}"))
				.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(JSON.readTree(submitted).get("storyId").asString());
	}

	private UUID givenAdmin() {
		UUID userId = this.users.save(User.register(ADMIN_PLAYER_REF, null, Instant.now())).getId();
		// 역할 승격 경로를 프로덕션 코드에 만들지 않기 위해서다 (S-11).
		new org.springframework.jdbc.core.JdbcTemplate(this.identity)
				.update("UPDATE \"user\" SET role = 'admin' WHERE id = ?", userId);
		return userId;
	}

	private String stepUpToken() throws Exception {
		String enrolled = this.mvc
				.perform(post("/api/v1/admin/2fa/enroll").with(asPlayer(ADMIN_PLAYER_REF)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String secret = JSON.readTree(enrolled).get("secret").asText();

		String confirmed = this.mvc.perform(post("/api/v1/admin/2fa/confirm")
						.with(asPlayer(ADMIN_PLAYER_REF)).contentType(MediaType.APPLICATION_JSON)
						.content("{\"code\":\"%s\"}".formatted(codeFor(secret))))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JSON.readTree(confirmed).get("stepUpToken").asText();
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

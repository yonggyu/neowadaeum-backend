package com.neowadaeum.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.authoring.draft.StoryDraftRepository;
import com.neowadaeum.authoring.review.StoryReviewRepository;
import com.neowadaeum.identity.access.AdminAccessGuard;
import com.neowadaeum.identity.domain.User;
import com.neowadaeum.identity.repository.UserRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * §13-63 (#316) — <b>지난 판정을 실제 요청 경로에서 볼 수 있는가.</b>
 *
 * <p>여기서만 확인할 수 있는 것: 검수 이력이 <b>시간 역순</b>으로 나오는지, 자동과 사람이
 * <b>구분된 채로</b> 함께 오는지, 그리고 <b>{@code player_ref} 가 새지 않는지</b> (I-3).
 *
 * <p><b>S-11 — 픽스처는 전부 가상의 문자열이다.</b>
 */
class AdminReviewHistoryApiIntegrationTests extends ContainerTestBase {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final UUID ADMIN_PLAYER_REF = UUID.fromString("00000000-0000-4000-8000-0000000000e3");

	/** 어떤 표기의 UUID 도 응답에 없어야 한다 — 있어야 할 것만 단언하면 값이 새어도 통과한다. */
	private static final Pattern ANY_UUID = Pattern
			.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

	private static final String PAYLOAD = "{\"title\":\"여름의 도서관\",\"shortDesc\":\"소개\","
			+ "\"worldIntro\":\"소개\",\"worldPrompt\":\"여름의 도서관에서 시작한다.\","
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
	@Qualifier("identityDataSource")
	private DataSource identity;

	@Autowired
	@Qualifier("catalogDataSource")
	private DataSource catalog;

	private final java.util.List<UUID> extraStories = new java.util.ArrayList<>();

	@AfterEach
	void clear() {
		JdbcClient jdbc = JdbcClient.create(this.catalog);
		java.util.Set<UUID> storyIds = new java.util.LinkedHashSet<>(this.extraStories);
		this.reviews.findAll().forEach(review -> storyIds.add(review.getStoryId()));
		for (UUID storyId : storyIds) {
			jdbc.sql("DELETE FROM chapter_def WHERE story_id = ?").param(storyId).update();
			jdbc.sql("DELETE FROM ending_def WHERE story_id = ?").param(storyId).update();
			jdbc.sql("UPDATE story SET current_version_id = NULL WHERE id = ?").param(storyId).update();
			jdbc.sql("DELETE FROM story_version WHERE story_id = ?").param(storyId).update();
			jdbc.sql("DELETE FROM story WHERE id = ?").param(storyId).update();
		}
		this.extraStories.clear();
		this.reviews.deleteAll();
		this.drafts.deleteAll();
		this.users.findByPlayerRef(ADMIN_PLAYER_REF).ifPresent(this.users::delete);
	}

	/**
	 * <b>최근 판정부터 온다</b> (§13-63).
	 *
	 * <p>이력은 append-only 이므로 (I-5) 오래된 것이 앞에 오면 화면은 <b>이번 회차의 답</b>을
	 * 목록 끝에서 찾아야 한다.
	 */
	@Test
	void S13_63_the_history_returns_verdicts_newest_first() throws Exception {
		givenAdmin();
		UUID storyId = givenPublicSubmission();
		String stepUp = stepUpToken();
		this.mvc.perform(verdict(storyId, stepUp, "{\"verdict\":\"HOLD\"}")).andExpect(status().isOk());
		this.mvc.perform(verdict(storyId, stepUp, "{\"verdict\":\"PASS\"}")).andExpect(status().isOk());

		this.mvc.perform(history(storyId, stepUp))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(3))
				.andExpect(jsonPath("$[0].verdict").value("pass"))
				.andExpect(jsonPath("$[1].verdict").value("hold"))
				// 회차의 시작 — 제출이 남긴 자동 통과다 (§13-57).
				.andExpect(jsonPath("$[2].verdict").value("pass"));
	}

	/**
	 * <b>자동과 사람이 구분된 채로 함께 온다</b> (R8.6).
	 *
	 * <p>자동 통과는 <b>사람이 본 것이 아니다.</b> 구분이 없으면 화면은 아무도 보지 않은 작품을
	 * 승인된 작품으로 적는다.
	 */
	@Test
	void S13_63_the_history_tells_the_automatic_stage_from_the_human_one() throws Exception {
		givenAdmin();
		UUID storyId = givenPublicSubmission();
		String stepUp = stepUpToken();
		this.mvc.perform(verdict(storyId, stepUp,
						"{\"verdict\":\"REJECT\",\"reasons\":[\"RATING_EXCEEDED\"],\"note\":\"내부 기록\"}"))
				.andExpect(status().isOk());

		this.mvc.perform(history(storyId, stepUp))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].stage").value("human"))
				// 사유는 카테고리만이다 (R8.7) — 저장된 표기 그대로 나온다.
				.andExpect(jsonPath("$[0].reasons[0]").value("rating_exceeded"))
				// note 는 관리자 전용 경로에서만 보인다 — 작성자 응답은 카테고리만 싣는다 (S-11).
				.andExpect(jsonPath("$[0].note").value("내부 기록"))
				.andExpect(jsonPath("$[1].stage").value("auto"))
				.andExpect(jsonPath("$[1].note").isEmpty());
	}

	/**
	 * <b>{@code reviewer_ref} 는 응답에 나가지 않는다</b> (I-3).
	 *
	 * <p>그 값은 {@code player_ref} 다. 관리자 화면이라는 이유로 실으면 <b>화면 하나가 회원
	 * 식별자를 나르는 통로</b>가 된다 — 누가 판정했는지는 감사 기록이 답한다 (R14.5).
	 */
	@Test
	void I3_the_history_carries_no_player_ref() throws Exception {
		givenAdmin();
		UUID storyId = givenPublicSubmission();
		String stepUp = stepUpToken();
		this.mvc.perform(verdict(storyId, stepUp, "{\"verdict\":\"PASS\"}")).andExpect(status().isOk());

		String body = this.mvc.perform(history(storyId, stepUp)).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		assertThat(body).doesNotContain(ADMIN_PLAYER_REF.toString());
		assertThat(ANY_UUID.matcher(body).find())
				.as("이력 응답에 UUID 가 하나도 없어야 한다 — 새는 자리는 검수자만이 아니다 (I-3)")
				.isFalse();
	}

	/**
	 * <b>판정이 없으면 빈 목록이다</b> — 없는 작품과 다른 사실이다 (§13-63).
	 *
	 * <p>미리보기로만 만들어진 작품은 존재한다 (§13-5). {@code 404} 로 답하면 화면은 <b>없는
	 * 작품</b>이라고 적는다.
	 */
	@Test
	void I5_a_story_with_no_verdict_yet_has_an_empty_history() throws Exception {
		givenAdmin();
		UUID storyId = givenStoryWithoutAnyReview();
		String stepUp = stepUpToken();

		this.mvc.perform(history(storyId, stepUp))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}

	/** 없는 작품은 {@code 404} 다. 지워진 작품도 없는 작품이다 (§13-58). */
	@Test
	void S13_63_an_unknown_story_has_no_history() throws Exception {
		givenAdmin();
		String stepUp = stepUpToken();

		this.mvc.perform(history(UUID.randomUUID(), stepUp)).andExpect(status().isNotFound());
	}

	/**
	 * <b>세 조건은 이 문에도 서 있다</b> (S-4).
	 *
	 * <p>이력에는 검수자가 적은 내부 기록이 실린다 — 그것이 새면 <b>왜 내렸는지</b>가 밖으로
	 * 나가고, 그것이 곧 우회 학습이 된다 (S-11).
	 */
	@Test
	void SEC4_without_a_step_up_the_history_is_not_visible() throws Exception {
		givenAdmin();
		UUID storyId = givenPublicSubmission();

		this.mvc.perform(get("/api/v1/admin/reviews/%s/history".formatted(storyId))
						.with(asPlayer(ADMIN_PLAYER_REF)))
				.andExpect(status().isForbidden());
	}

	/** 관리자가 아니면 이력도 보이지 않는다 (S-4). 거절 이유는 응답이 말하지 않는다 (S-6). */
	@Test
	void SEC4_a_member_who_is_not_an_admin_cannot_read_the_history() throws Exception {
		givenAdmin();
		UUID storyId = givenPublicSubmission();

		this.mvc.perform(get("/api/v1/admin/reviews/%s/history".formatted(storyId))
						.with(asPlayer(TEST_PLAYER_REF)))
				.andExpect(status().isForbidden());
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder history(
			UUID storyId, String stepUp) {
		return get("/api/v1/admin/reviews/%s/history".formatted(storyId))
				.with(asPlayer(ADMIN_PLAYER_REF)).header(AdminAccessGuard.STEP_UP_HEADER, stepUp);
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

	/**
	 * <b>검수 이력이 하나도 없는 작품.</b>
	 *
	 * <p>미리보기가 만드는 자리다 (§13-5) — 그 경로를 통째로 태우면 이 테스트가 확인하려는
	 * 것(빈 목록)이 아니라 미리보기 파이프라인을 확인하게 된다.
	 */
	private UUID givenStoryWithoutAnyReview() {
		UUID storyId = UUID.randomUUID();
		JdbcClient.create(this.catalog).sql("""
						INSERT INTO story (id, slug, title, short_desc, world_intro, author_type,
								author_ref, visibility, review_status, created_at)
						VALUES (?, ?, ?, ?, ?, 'user', ?, 'private', 'draft', ?)
						""")
				.params(storyId, "story-" + storyId, "가을의 정원", "소개", "소개", UUID.randomUUID(),
						OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC))
				.update();
		this.extraStories.add(storyId);
		return storyId;
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

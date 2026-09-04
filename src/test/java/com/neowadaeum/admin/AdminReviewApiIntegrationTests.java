package com.neowadaeum.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.ai.log.AccessAuditLogRepository;
import com.neowadaeum.ai.log.AdminAuditLogRepository;
import com.neowadaeum.authoring.draft.StoryDraftRepository;
import com.neowadaeum.authoring.report.ContentReport;
import com.neowadaeum.authoring.report.ContentReportRepository;
import com.neowadaeum.authoring.report.ReportReason;
import com.neowadaeum.authoring.report.ReportTarget;
import com.neowadaeum.authoring.review.StoryReviewRepository;
import com.neowadaeum.identity.access.AdminAccessGuard;
import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.domain.SafetyVerdict;
import com.neowadaeum.play.domain.Turn;
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
import tools.jackson.databind.JsonNode;
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

	/** 신고자가 쓴 자유 문장. <b>응답에 나가지 않는 것</b>을 확인하는 데 쓴다 (I-3, §13-62). */
	private static final String REPORT_DETAIL = "신고자가 쓴 문장이다";

	private static final String PAYLOAD = "{\"title\":\"봄의 학교\",\"shortDescription\":\"소개\","
			+ "\"worldIntro\":\"소개\",\"settingDetail\":\"봄의 학교에서 시작한다.\","
			+ "\"chapters\":[{\"title\":\"1장\",\"summarySeed\":\"시작\"}],"
			+ "\"endings\":[{\"label\":\"좋은 끝\",\"epilogueText\":\"잘 끝났다.\"}]}";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private UserRepository users;

	@Autowired
	private StoryDraftRepository drafts;

	/** #332 — 미리보기 턴은 play 스토어의 것이다. 검수 상세가 원고를 거쳐 그것에 닿는다. */
	@Autowired
	private com.neowadaeum.play.repository.PlaySessionRepository playSessions;

	@Autowired
	private com.neowadaeum.play.repository.TurnRepository turns;

	@Autowired
	private StoryReviewRepository reviews;

	@Autowired
	private ContentReportRepository contentReports;

	@Autowired
	private AdminAuditLogRepository auditLogs;

	@Autowired
	private AccessAuditLogRepository accessLogs;

	@Autowired
	@Qualifier("identityDataSource")
	private DataSource identity;

	@Autowired
	@Qualifier("catalogDataSource")
	private DataSource catalog;

	/** #332 — 미리보기 세션은 play 스토어에 있다. 뒷정리도 그쪽에서 한다. */
	private final java.util.List<UUID> createdPreviewSessions = new java.util.ArrayList<>();

	@AfterEach
	void clear() {
		this.createdPreviewSessions.forEach(sessionId -> {
			this.turns.deleteAll(this.turns.findBySessionIdAndDeletedAtIsNullOrderByTurnNoAsc(sessionId));
			this.playSessions.deleteById(sessionId);
		});
		this.createdPreviewSessions.clear();
		JdbcClient jdbc = JdbcClient.create(this.catalog);
		this.reviews.findAll().forEach(review -> {
			UUID storyId = review.getStoryId();
			jdbc.sql("DELETE FROM chapter_def WHERE story_id = ?").param(storyId).update();
			jdbc.sql("DELETE FROM ending_def WHERE story_id = ?").param(storyId).update();
			jdbc.sql("UPDATE story SET current_version_id = NULL WHERE id = ?").param(storyId).update();
			jdbc.sql("DELETE FROM story_version WHERE story_id = ?").param(storyId).update();
			jdbc.sql("DELETE FROM story WHERE id = ?").param(storyId).update();
		});
		this.contentReports.deleteAll();
		this.reviews.deleteAll();
		this.drafts.deleteAll();
		this.auditLogs.deleteAll();
		this.accessLogs.deleteAll();
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

	/**
	 * <b>사람이 손으로 내린다</b> (§13-64, R8.9) — 승인된 작품이 실제 요청 경로에서 내려간다.
	 *
	 * <p>자동 정지는 신고 임계가 하지만, 임계에 닿지 않은 것을 사람이 보고 내려야 할 때가 있다.
	 */
	@Test
	void S13_64_a_manual_suspend_takes_an_approved_story_down() throws Exception {
		givenAdmin();
		UUID storyId = givenPublicSubmission();
		String stepUp = stepUpToken();
		this.mvc.perform(verdict(storyId, stepUp, "{\"verdict\":\"PASS\"}")).andExpect(status().isOk());

		this.mvc.perform(verdict(storyId, stepUp, "{\"verdict\":\"SUSPEND\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reviewStatus").value("suspended"));
	}

	/**
	 * <b>정지는 관리자만 내린다</b> (S-4, §14).
	 *
	 * <p>작품을 내리는 것은 <b>되돌릴 수 있는</b> 판단이지만, 아무나 내릴 수 있으면 그것은
	 * 검수가 아니라 <b>누구나 쓸 수 있는 서비스 거부</b>다.
	 */
	@Test
	void SEC4_a_manual_suspend_requires_an_admin() throws Exception {
		givenAdmin();
		UUID storyId = givenPublicSubmission();
		String stepUp = stepUpToken();
		this.mvc.perform(verdict(storyId, stepUp, "{\"verdict\":\"PASS\"}")).andExpect(status().isOk());

		this.mvc.perform(post("/api/v1/admin/reviews/%s/verdict".formatted(storyId))
						.with(asPlayer(UUID.randomUUID())).contentType(MediaType.APPLICATION_JSON)
						.content("{\"verdict\":\"SUSPEND\"}"))
				.andExpect(status().isForbidden());

		this.mvc.perform(get("/api/v1/admin/reviews").with(asPlayer(ADMIN_PLAYER_REF))
						.header(AdminAccessGuard.STEP_UP_HEADER, stepUp))
				.andExpect(jsonPath("$[?(@.storyId=='%s')]".formatted(storyId)).doesNotExist());
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

	// ── 이 작품에 무엇이 신고됐는가 (§13-62, 이슈 #316) ────────

	/**
	 * <b>사유별로 몇 건인지가 먼저 온다</b> (§13-62).
	 *
	 * <p>검수자가 판정에 쓰는 것이 이 숫자다 — 없으면 제목과 상태만 보고 승인/반려하게 된다.
	 */
	@Test
	void S13_62_the_reports_are_tallied_by_reason() throws Exception {
		givenAdmin();
		UUID storyId = givenPublicSubmission();
		givenReport(storyId, ReportReason.INAPPROPRIATE);
		givenReport(storyId, ReportReason.INAPPROPRIATE);
		givenReport(storyId, ReportReason.IP_VIOLATION);
		String stepUp = stepUpToken();

		this.mvc.perform(reports(storyId, stepUp)).andExpect(status().isOk())
				.andExpect(jsonPath("$.storyId").value(storyId.toString()))
				.andExpect(jsonPath("$.reasonCounts[?(@.reason=='inappropriate')].count").value(2))
				.andExpect(jsonPath("$.reasonCounts[?(@.reason=='ip_violation')].count").value(1));
	}

	/**
	 * <b>개별 신고도 함께 온다</b> (§13-62) — 접수 시각 · 상태 · 대상 턴.
	 *
	 * <p>{@code turnNo} 는 <b>작품 신고에 없다.</b> 턴 신고에서 작품을 알아내려면 {@code play}
	 * 의 세션 표를 읽어야 하므로 (§13-41, §5.3) 여기 오는 것은 작품 신고뿐이며, 키는 생략하지
	 * 않고 {@code null} 로 명시한다.
	 */
	@Test
	void S13_62_each_report_carries_its_time_status_and_turn_slot() throws Exception {
		givenAdmin();
		UUID storyId = givenPublicSubmission();
		givenReport(storyId, ReportReason.REAL_PERSON);
		String stepUp = stepUpToken();

		String body = this.mvc.perform(reports(storyId, stepUp)).andExpect(status().isOk())
				.andExpect(jsonPath("$.reports[0].reason").value("real_person"))
				.andExpect(jsonPath("$.reports[0].status").value("open"))
				.andExpect(jsonPath("$.reports[0].createdAt").isNotEmpty())
				.andReturn().getResponse().getContentAsString();

		assertThat(body).as("nullable 필드는 키를 생략하지 않고 null 로 명시한다").contains("\"turnNo\":null");
	}

	/**
	 * <b>신고자가 응답에 없다</b> (I-3, §13-62).
	 *
	 * <p>누가 신고했는지는 판정에 쓰이지 않으며, 쓰이지 않는 식별자는 담을 이유가 없다.
	 * 신고자가 쓴 자유 문장도 나가지 않는다 — 그 안에는 신고자를 특정하는 말이 들어 있다.
	 *
	 * <p><b>"있어야 할 것"만 단언하면 값이 새어도 통과한다</b> — 그래서 없는 것을 본다.
	 */
	@Test
	void I3_the_reports_response_does_not_carry_the_reporter() throws Exception {
		givenAdmin();
		UUID storyId = givenPublicSubmission();
		UUID reporterRef = givenReport(storyId, ReportReason.OTHER);
		String stepUp = stepUpToken();

		String body = this.mvc.perform(reports(storyId, stepUp)).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		assertThat(body).doesNotContain("reporterRef").doesNotContain(reporterRef.toString())
				.doesNotContain("detail").doesNotContain(REPORT_DETAIL);
	}

	/** <b>승격 없이는 신고 내용이 보이지 않는다</b> (S-4) — 형제 경로와 같은 문이다. */
	@Test
	void SEC4_without_a_step_up_the_reports_are_not_visible() throws Exception {
		givenAdmin();
		UUID storyId = givenPublicSubmission();

		this.mvc.perform(get("/api/v1/admin/reviews/%s/reports".formatted(storyId))
						.with(asPlayer(ADMIN_PLAYER_REF)))
				.andExpect(status().isForbidden());
	}

	/**
	 * <b>승격 없이는 원고가 열리지 않는다</b> (S-4, I-8).
	 *
	 * <p>여기 열리는 것은 <b>아직 아무도 보지 못한 작품의 원문</b>이며, 그것이 새면 검수 전
	 * UGC 가 새는 것과 같다.
	 */
	@Test
	void SEC4_without_a_step_up_the_manuscript_is_not_visible() throws Exception {
		givenAdmin();
		UUID storyId = givenPublicSubmission();

		this.mvc.perform(get("/api/v1/admin/reviews/%s".formatted(storyId))
						.with(asPlayer(ADMIN_PLAYER_REF)))
				.andExpect(status().isForbidden());
	}

	/** 없는 작품은 {@code 404} 다 — 빈 목록으로 답하면 오타 난 id 가 "신고 없음"으로 보인다. */
	@Test
	void S13_62_reports_for_an_unknown_story_are_not_found() throws Exception {
		givenAdmin();
		String stepUp = stepUpToken();

		this.mvc.perform(reports(UUID.randomUUID(), stepUp)).andExpect(status().isNotFound());
	}

	/** <b>읽는 것도 감사에 남는다</b> (R14.5) — 누가 언제 신고 내용을 열었는지. */
	@Test
	void R14_5_reading_the_reports_is_audited() throws Exception {
		UUID adminUserId = givenAdmin();
		UUID storyId = givenPublicSubmission();
		givenReport(storyId, ReportReason.INAPPROPRIATE);
		String stepUp = stepUpToken();

		this.mvc.perform(reports(storyId, stepUp)).andExpect(status().isOk());

		assertThat(this.auditLogs.findByAdminUserIdOrderByCreatedAtDesc(adminUserId, Limit.of(10)))
				.anySatisfy(log -> {
					assertThat(log.getAction()).isEqualTo("admin.review.reports.read");
					assertThat(log.getTargetId()).isEqualTo(storyId);
				});
	}

	/**
	 * <b>검수자가 판정에 쓸 것이 온다</b> (#316, §13-61).
	 *
	 * <p>큐는 제목과 상태만 준다 — 그것만 보고 누르는 승인은 검수가 아니다.
	 */
	@Test
	void S13_61_the_manuscript_carries_what_a_verdict_needs() throws Exception {
		givenAdmin();
		UUID storyId = givenPublicSubmission();
		String stepUp = stepUpToken();

		this.mvc.perform(manuscript(storyId, stepUp))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.storyId").value(storyId.toString()))
				.andExpect(jsonPath("$.title").value("봄의 학교"))
				.andExpect(jsonPath("$.reviewStatus").value("in_review"))
				.andExpect(jsonPath("$.worldPrompt").value("봄의 학교에서 시작한다."))
				.andExpect(jsonPath("$.chapters[0].title").value("1장"))
				.andExpect(jsonPath("$.endings[0].label").value("좋은 끝"))
				.andExpect(jsonPath("$.endings[0].epilogueText").value("잘 끝났다."))
				.andExpect(jsonPath("$.autoCheck.verdict").value("pass"));
	}

	/**
	 * <b>원문을 읽은 사실이 남는다</b> (R12.3, S-5).
	 *
	 * <p>{@code ReviewQueueItem} 이 <b>"원문 열람은 감사가 걸린 다른 문"</b>이라고 적은 그
	 * 감사다 — 행위 기록(R14.5)과 별개의 표이며, 남기지 못하면 원문이 나가지 않는다.
	 */
	@Test
	void R12_3_reading_a_manuscript_is_audited() throws Exception {
		UUID adminUserId = givenAdmin();
		UUID storyId = givenPublicSubmission();
		String stepUp = stepUpToken();

		this.mvc.perform(manuscript(storyId, stepUp)).andExpect(status().isOk());

		assertThat(this.accessLogs.findByResourceAndResourceIdOrderByCreatedAtDesc("story_draft",
				storyId, Limit.of(10)))
				.singleElement()
				.satisfies(log -> assertThat(log.getAdminUserId()).isEqualTo(adminUserId));
		assertThat(this.auditLogs.findByAdminUserIdOrderByCreatedAtDesc(adminUserId, Limit.of(10)))
				.anySatisfy(log -> {
					assertThat(log.getAction()).isEqualTo("admin.review.manuscript");
					assertThat(log.getTargetId()).isEqualTo(storyId);
				});
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder reports(
			UUID storyId, String stepUp) {
		return get("/api/v1/admin/reviews/%s/reports".formatted(storyId))
				.with(asPlayer(ADMIN_PLAYER_REF)).header(AdminAccessGuard.STEP_UP_HEADER, stepUp);
	}

	/**
	 * 한 사람이 이 작품을 신고한다.
	 *
	 * <p><b>접수 경로가 아니라 표에 직접 넣는다.</b> 확인하려는 것은 <b>읽는 문</b>이고, 접수
	 * 경로에는 IP 기준 레이트리밋이 걸려 있어 (S-8) 픽스처가 그것에 먼저 막힌다.
	 */
	private UUID givenReport(UUID storyId, ReportReason reason) {
		UUID reporterRef = UUID.randomUUID();
		this.contentReports.save(ContentReport.of(reporterRef, ReportTarget.STORY, storyId, null,
				null, reason, REPORT_DETAIL, Instant.now()));
		return reporterRef;
	}

	/**
	 * <b>작성자는 표시명으로만 온다</b> (§13-7, I-3).
	 *
	 * <p>있어야 할 것만 단언하면 식별자가 새어도 통과한다 — 응답 본문에 {@code player_ref} 가
	 * 없다는 것을 함께 건다 (S-11).
	 */
	@Test
	void I3_the_manuscript_carries_no_player_ref() throws Exception {
		givenAdmin();
		UUID authorRef = UUID.randomUUID();
		UUID storyId = givenPublicSubmission(authorRef);
		String stepUp = stepUpToken();

		String body = this.mvc.perform(manuscript(storyId, stepUp)).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		assertThat(body).doesNotContain(authorRef.toString()).doesNotContain("playerRef")
				.doesNotContain("player_ref").doesNotContain("authorRef");
	}

	/** 없는 작품은 {@code 404} 다 — 그리고 열람 기록도 남지 않는다. */
	@Test
	void S13_61_an_unknown_story_has_no_manuscript() throws Exception {
		UUID adminUserId = givenAdmin();
		String stepUp = stepUpToken();

		this.mvc.perform(manuscript(UUID.randomUUID(), stepUp)).andExpect(status().isNotFound());

		assertThat(this.accessLogs.findByAdminUserIdOrderByCreatedAtDesc(adminUserId, Limit.of(10)))
				.isEmpty();
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder manuscript(
			UUID storyId, String stepUp) {
		return get("/api/v1/admin/reviews/%s".formatted(storyId)).with(asPlayer(ADMIN_PLAYER_REF))
				.header(AdminAccessGuard.STEP_UP_HEADER, stepUp);
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder verdict(
			UUID storyId, String stepUp, String body) {
		return post("/api/v1/admin/reviews/%s/verdict".formatted(storyId))
				.with(asPlayer(ADMIN_PLAYER_REF)).header(AdminAccessGuard.STEP_UP_HEADER, stepUp)
				.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	/** 작성자가 {@code public} 으로 제출한다 — 그것이 큐에 들어오는 유일한 길이다 (R8.6). */
	/**
	 * <b>#332 · §13-68 — 검수자가 미리보기 턴을 본다.</b>
	 *
	 * <p>미리보기는 여전히 다른 작품을 만들지만 (§13-5) 원고가 그 세션을 기억하므로 원고를
	 * 거쳐 갈 수 있다. 이것 없이 내리는 승인은 <b>프롬프트만 읽고 내린 승인</b>이다.
	 */
	@Test
	void S13_68_the_manuscript_carries_the_preview_turns() throws Exception {
		givenAdmin();
		UUID storyId = givenPublicSubmission();
		givenPreviewOn(storyId);

		JsonNode body = JSON.readTree(this.mvc
				.perform(get("/api/v1/admin/reviews/%s".formatted(storyId))
						.with(asPlayer(ADMIN_PLAYER_REF))
						.header(AdminAccessGuard.STEP_UP_HEADER, stepUpToken()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

		assertThat(body.path("previewTurns")).hasSize(1);
		assertThat(body.path("previewTurns").get(0).path("paragraphs").asString())
				.contains("미리보기 본문");
		assertThat(body.path("previewedAt").isNull()).isFalse();
	}

	/**
	 * <b>비어 있는 것이 실패가 아니다</b> (#332).
	 *
	 * <p>미리보기를 돌리지 않았거나 그것이 보관 기간을 넘겨 파기되었다 (§13-37). 여기서 404 를
	 * 내면 <b>검수 상세 전체가 열리지 않는다.</b>
	 */
	@Test
	void S13_68_a_manuscript_without_a_preview_opens_anyway() throws Exception {
		givenAdmin();
		UUID storyId = givenPublicSubmission();

		JsonNode body = JSON.readTree(this.mvc
				.perform(get("/api/v1/admin/reviews/%s".formatted(storyId))
						.with(asPlayer(ADMIN_PLAYER_REF))
						.header(AdminAccessGuard.STEP_UP_HEADER, stepUpToken()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

		assertThat(body.path("previewTurns")).isEmpty();
		assertThat(body.path("previewedAt").isNull()).isTrue();
	}

	/**
	 * 미리보기가 만든 것을 원고에 붙인다 — {@code PreviewController} 가 하는 일을 그대로
	 * 흉내 낸다. API 로 돌리면 시나리오 Provider 와 일일 상한이 이 테스트에 딸려 온다.
	 */
	private void givenPreviewOn(UUID storyId) {
		UUID previewSessionId = givenPreviewSession();
		JdbcClient.create(this.catalog).sql("""
						UPDATE story_draft SET preview_story_id = ?, preview_session_id = ?,
						                       previewed_at = NOW()
						WHERE story_id = ?
						""")
				.params(UUID.randomUUID(), previewSessionId, storyId).update();
	}

	private UUID givenPreviewSession() {
		Instant now = Instant.now();
		PlaySession session = this.playSessions.save(PlaySession.start(UUID.randomUUID(),
				UUID.randomUUID(), UUID.randomUUID(), "fixed", "scenario", true, now));
		session.recordTurn(1, 1, now);
		this.turns.save(Turn.create(new Turn.TurnDraft(session.getId(), 1, 1,
				"[\"미리보기 본문\"]", "[]", null, false, false, null, SafetyVerdict.PASS, true, false),
				now));
		this.playSessions.saveAndFlush(session);
		this.createdPreviewSessions.add(session.getId());
		return session.getId();
	}

	private UUID givenPublicSubmission() throws Exception {
		return givenPublicSubmission(UUID.randomUUID());
	}

	private UUID givenPublicSubmission(UUID authorRef) throws Exception {
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

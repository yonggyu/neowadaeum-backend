package com.neowadaeum.authoring.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.authoring.draft.DraftService;
import com.neowadaeum.authoring.draft.StoryDraftRepository;
import com.neowadaeum.authoring.report.ContentReportRepository;
import com.neowadaeum.authoring.report.SuspensionThresholds;
import com.neowadaeum.authoring.review.StoryReviewRepository;
import com.neowadaeum.authoring.review.SubmissionService;
import com.neowadaeum.authoring.review.Visibility;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * B-57 — 신고가 <b>실제 요청 경로에서</b> 동작한다 (§13.9, R13.5).
 *
 * <p>여기서만 확인할 수 있는 것: 계약이 적은 <b>소문자 표기</b>를 받는지, 그리고 응답이
 * <b>아무것도 흘리지 않는지</b> (S-11).
 */
class ReportApiIntegrationTests extends ContainerTestBase {

	private static final String PAYLOAD = "{\"title\":\"봄의 학교\",\"shortDesc\":\"소개\","
			+ "\"worldIntro\":\"소개\",\"worldPrompt\":\"봄의 학교에서 시작한다.\","
			+ "\"chapters\":[{\"title\":\"1장\",\"summarySeed\":\"시작\"}],"
			+ "\"endings\":[{\"label\":\"좋은 끝\",\"epilogueText\":\"잘 끝났다.\"}]}";

	/** 회선을 테스트마다 갈라 두는 카운터. 한도가 IP 기준이기 때문이다 (S-8). */
	private static final java.util.concurrent.atomic.AtomicInteger ADDRESSES =
			new java.util.concurrent.atomic.AtomicInteger();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private SubmissionService submissions;

	@Autowired
	private DraftService drafts;

	@Autowired
	private StoryDraftRepository draftRows;

	@Autowired
	private StoryReviewRepository reviews;

	@Autowired
	private ContentReportRepository reports;

	@Autowired
	@Qualifier("catalogDataSource")
	private DataSource catalog;

	private final List<UUID> stories = new java.util.ArrayList<>();

	@AfterEach
	void clear() {
		JdbcClient jdbc = JdbcClient.create(this.catalog);
		this.reports.deleteAll();
		this.reviews.deleteAll();
		for (UUID storyId : this.stories) {
			jdbc.sql("DELETE FROM chapter_def WHERE story_id = ?").param(storyId).update();
			jdbc.sql("DELETE FROM ending_def WHERE story_id = ?").param(storyId).update();
			jdbc.sql("UPDATE story SET current_version_id = NULL WHERE id = ?").param(storyId).update();
			jdbc.sql("DELETE FROM story_version WHERE story_id = ?").param(storyId).update();
			jdbc.sql("DELETE FROM story WHERE id = ?").param(storyId).update();
		}
		this.stories.clear();
		this.draftRows.deleteAll();
		jdbc.sql("DELETE FROM service_config WHERE config_key = ?")
				.param(SuspensionThresholds.CONFIG_KEY).update();
	}

	/**
	 * 접수는 {@code 202} 이고 <b>본문이 없다</b> (S-11).
	 *
	 * <p>임계에 닿았는지를 응답으로 알리면 신고자가 <b>임계를 역산할 수 있다.</b>
	 */
	@Test
	void R13_5_a_report_is_accepted_with_no_body() throws Exception {
		UUID storyId = givenApprovedStory();

		this.mvc.perform(report("""
						{"targetType":"story","targetId":"%s","reason":"inappropriate"}""".formatted(storyId)))
				.andExpect(status().isAccepted())
				.andExpect(content().string(""));

		assertThat(this.reports.countByTargetTypeAndTargetId("story", storyId)).isEqualTo(1);
	}

	/** 턴 신고는 어느 플레이에서 나왔는지를 함께 나른다 (§13.9). */
	@Test
	void R13_5_a_turn_report_carries_its_session() throws Exception {
		UUID turnId = UUID.randomUUID();

		this.mvc.perform(report("""
						{"targetType":"turn","targetId":"%s","sessionId":"%s","turnNo":12,\
						"reason":"real_person","detail":"설명"}"""
				.formatted(turnId, UUID.randomUUID())))
				.andExpect(status().isAccepted());

		assertThat(this.reports.countByTargetTypeAndTargetId("turn", turnId)).isEqualTo(1);
	}

	/**
	 * 두 번째 신고는 {@code 409} 다 (§13.9).
	 *
	 * <p><b>같은 사람이어야 한다.</b> 신고자가 다르면 그것은 중복이 아니라 두 번째 사람이고,
	 * 그 둘을 헷갈리면 누적 임계가 무의미해진다.
	 */
	@Test
	void R8_9_reporting_the_same_target_twice_is_a_conflict() throws Exception {
		UUID storyId = givenApprovedStory();
		UUID reporter = UUID.randomUUID();
		String body = """
				{"targetType":"story","targetId":"%s","reason":"other"}""".formatted(storyId);
		this.mvc.perform(from(uniqueAddress(), reporter, body)).andExpect(status().isAccepted());

		this.mvc.perform(from(uniqueAddress(), reporter, body)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("ALREADY_EXISTS"));
	}

	/** 없는 작품은 {@code 404} 다. */
	@Test
	void R8_9_an_unknown_story_is_not_found() throws Exception {
		this.mvc.perform(report("""
						{"targetType":"story","targetId":"%s","reason":"other"}"""
				.formatted(UUID.randomUUID())))
				.andExpect(status().isNotFound());
	}

	/** 계약이 소문자로 적었다 — 열거형 이름을 그대로 보내면 검증에서 걸린다 (§13.9). */
	@Test
	void R13_5_an_unknown_reason_is_a_validation_error() throws Exception {
		UUID storyId = givenApprovedStory();

		this.mvc.perform(report("""
						{"targetType":"story","targetId":"%s","reason":"INAPPROPRIATE"}""".formatted(storyId)))
				.andExpect(status().isBadRequest());
	}

	/** 대상 종류가 빠지면 검증에서 걸린다 (§9.1). */
	@Test
	void R13_5_a_missing_target_type_is_a_validation_error() throws Exception {
		UUID storyId = givenApprovedStory();

		this.mvc.perform(report("""
						{"targetId":"%s","reason":"other"}""".formatted(storyId)))
				.andExpect(status().isBadRequest());
	}

	/**
	 * <b>IP 기준 한도를 넘기면 {@code 429} 다</b> (S-8).
	 *
	 * <p>계정 기준만으로는 계정을 여러 개 만들어 임계를 채우는 길이 열려 있다 — 신고는
	 * <b>작품을 내릴 수 있는</b> 요청이다.
	 */
	@Test
	void SEC8_too_many_reports_from_one_address_are_refused() throws Exception {
		UUID storyId = givenApprovedStory();
		String oneAddress = uniqueAddress();
		String body = """
				{"targetType":"story","targetId":"%s","reason":"other"}""".formatted(storyId);
		boolean refused = false;

		// 한도보다 넉넉히 부른다. 신고자가 매번 달라 409 는 나지 않으므로 남는 것은 한도뿐이다.
		for (int i = 0; i < 40 && !refused; i++) {
			refused = this.mvc.perform(from(oneAddress, body)).andReturn().getResponse()
					.getStatus() == 429;
		}

		assertThat(refused).as("S-8 — 한 회선이 낼 수 있는 신고 수에 한도가 있다").isTrue();
	}

	/**
	 * <b>테스트마다 다른 회선에서 온다.</b>
	 *
	 * <p>한도는 IP 기준이고 MockMvc 는 모든 요청에 같은 주소를 준다 — 그대로 두면 앞선
	 * 테스트가 뒤 테스트의 한도를 갉아먹고, 실패가 <b>순서에 따라</b> 나타난다.
	 */
	private MockHttpServletRequestBuilder report(String body) {
		return from(uniqueAddress(), body);
	}

	private MockHttpServletRequestBuilder from(String remoteAddr, String body) {
		return from(remoteAddr, UUID.randomUUID(), body);
	}

	private MockHttpServletRequestBuilder from(String remoteAddr, UUID reporter, String body) {
		return post("/api/v1/reports").with(asPlayer(reporter)).with(request -> {
			request.setRemoteAddr(remoteAddr);
			return request;
		}).contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private String uniqueAddress() {
		int nth = ADDRESSES.incrementAndGet();
		return "10.%d.%d.%d".formatted(nth / 65536 % 256, nth / 256 % 256, nth % 256);
	}

	private UUID givenApprovedStory() {
		UUID authorRef = UUID.randomUUID();
		UUID draftId = this.drafts.create(authorRef).getId();
		this.drafts.save(authorRef, draftId, 5, PAYLOAD);
		UUID storyId = this.submissions.submit(authorRef, draftId, Visibility.UNLISTED).storyId();
		this.stories.add(storyId);
		return storyId;
	}
}

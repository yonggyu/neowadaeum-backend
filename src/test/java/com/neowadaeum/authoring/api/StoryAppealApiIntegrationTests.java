package com.neowadaeum.authoring.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.authoring.draft.StoryDraftRepository;
import com.neowadaeum.authoring.review.ReviewQueueService;
import com.neowadaeum.authoring.review.ReviewVerdict;
import com.neowadaeum.authoring.review.StoryAppealRepository;
import com.neowadaeum.authoring.review.StoryReviewRepository;
import com.neowadaeum.catalog.publish.StoryPublisher;
import java.util.List;
import java.util.Map;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * #290 · §13-59 — <b>자동으로 내려간 것을 사람이 다시 보는 길</b> (R8.9).
 *
 * <p><b>여기서 지키는 것은 세 문장이다.</b> 정지된 작품의 작성자는 재검토를 요청할 수 있다.
 * <b>그 요청이 작품의 상태를 바꾸지 않는다</b> — 정지된 작품은 이미 큐에 있다 (§13-41).
 * 그리고 <b>검수자가 그 사실을 본다</b> — 보이지 않는 기록은 아무에게도 닿지 않는다.
 *
 * <p><b>S-11 — 픽스처는 전부 가상의 문자열이다.</b>
 */
class StoryAppealApiIntegrationTests extends ContainerTestBase {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String PAYLOAD = "{\"title\":\"여름의 등대\",\"shortDesc\":\"소개\","
			+ "\"worldIntro\":\"소개\",\"worldPrompt\":\"여름의 등대에서 시작한다.\","
			+ "\"chapters\":[{\"title\":\"1장\",\"summarySeed\":\"시작\"}],"
			+ "\"endings\":[{\"label\":\"좋은 끝\",\"epilogueText\":\"잘 끝났다.\"}]}";

	private static final UUID REVIEWER_REF = UUID.fromString("00000000-0000-4000-8000-0000000000e2");

	/** 작성자가 쓴 사유. <b>검수자 말고 아무도 읽지 않는다</b>는 것을 이 값으로 대조한다. */
	private static final String REASON = "실존 인물이 아니라 창작 인물입니다. 다시 봐 주세요.";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ReviewQueueService queue;

	@Autowired
	private StoryPublisher publisher;

	@Autowired
	private StoryDraftRepository drafts;

	@Autowired
	private StoryReviewRepository reviews;

	@Autowired
	private StoryAppealRepository appeals;

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
		this.appeals.deleteAll();
		this.reviews.deleteAll();
		this.drafts.deleteAll();
	}

	/**
	 * <b>정지된 작품에는 말할 자리가 있다</b> (#290, R8.9).
	 *
	 * <p>이 경로가 없는 동안 정지 화면의 "이의가 있으면 문의해 주세요"는 <b>갈 곳 없는
	 * 안내</b>였고, 프론트는 그 문장과 버튼을 함께 뺐다.
	 */
	@Test
	void S13_59_a_suspended_story_can_be_appealed_by_its_author() throws Exception {
		UUID storyId = givenSuspendedStory();

		this.mvc.perform(appeal(storyId, REASON)).andExpect(status().isAccepted());

		assertThat(this.appeals.findAll()).singleElement()
				.satisfies(appeal -> assertThat(appeal.getStoryId()).isEqualTo(storyId));
	}

	/**
	 * <b>요청은 작품을 되돌리지 않는다</b> (R8.9, I-8).
	 *
	 * <p>자동으로 내린 것을 자동으로 올리지 않는다 — 작성자의 요청으로 상태가 움직이면
	 * <b>작성자가 검수 결과를 되돌리는</b> 길이 된다. 바뀌는 것은 기록과 신호뿐이다.
	 */
	@Test
	void I8_an_appeal_does_not_move_the_story_out_of_suspension() throws Exception {
		UUID storyId = givenSuspendedStory();

		this.mvc.perform(appeal(storyId, REASON)).andExpect(status().isAccepted());

		assertThat(column(storyId, "review_status")).isEqualTo("suspended");
		assertThat(column(storyId, "visibility")).isEqualTo("unlisted");
	}

	/**
	 * <b>검수자가 그 사실을 본다</b> (§13-59).
	 *
	 * <p>정지된 작품은 요청이 없어도 큐에 있다 (§13-41). 그래서 이 경로가 더하는 것은
	 * <b>표시</b>이며, 표시가 없으면 기록은 아무에게도 닿지 않는다.
	 */
	@Test
	void S13_59_the_reviewer_sees_that_the_author_appealed() throws Exception {
		UUID storyId = givenSuspendedStory();
		assertThat(appealedFlagOf(storyId)).as("요청 전에는 표시가 없다").isFalse();

		this.mvc.perform(appeal(storyId, REASON)).andExpect(status().isAccepted());

		assertThat(appealedFlagOf(storyId)).isTrue();
	}

	/**
	 * <b>사유는 검수자만 읽는다</b> (S-11).
	 *
	 * <p>작성자가 쓴 자유 문자열이 <b>새 노출면을 열지 않는 것</b>이 이 자리의 안전이다 —
	 * 요청의 응답에도, 검수 큐의 한 줄에도 실리지 않는다.
	 */
	@Test
	void SEC11_the_appeal_reason_is_not_echoed_anywhere() throws Exception {
		UUID storyId = givenSuspendedStory();

		String accepted = this.mvc.perform(appeal(storyId, REASON)).andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString();
		assertThat(accepted).doesNotContain(REASON);

		// 큐의 한 줄은 표시만 나른다. 사유는 원고와 같은 문으로 본다 (S-5).
		assertThat(this.queue.pending().toString()).doesNotContain(REASON);
	}

	/**
	 * <b>답을 받기 전에는 두 번 낼 수 없다</b> (§13-59).
	 *
	 * <p>무제한이면 <b>큐를 반복해 채울 수 있다</b> — 그러면 이 경로가 곧 큐를 무력화한다.
	 */
	@Test
	void S13_59_a_second_appeal_before_a_human_answers_is_refused() throws Exception {
		UUID storyId = givenSuspendedStory();
		this.mvc.perform(appeal(storyId, REASON)).andExpect(status().isAccepted());

		this.mvc.perform(appeal(storyId, REASON))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("ALREADY_EXISTS"));

		assertThat(this.appeals.findAll()).hasSize(1);
	}

	/**
	 * <b>한 번은 작품마다가 아니라 정지 건마다다</b> (§13-59).
	 *
	 * <p>작품마다 한 번으로 정하면 <b>한 번 복구된 작품은 두 번째 자동 정지에 아무 말도 못
	 * 하게 된다.</b> 사건을 닫는 것은 인간 판정이다 — 그 뒤의 정지는 새 사건이다.
	 */
	@Test
	void R8_9_a_new_suspension_after_a_human_verdict_opens_a_new_appeal() throws Exception {
		UUID storyId = givenSuspendedStory();
		this.mvc.perform(appeal(storyId, REASON)).andExpect(status().isAccepted());

		this.queue.decide(REVIEWER_REF, storyId, ReviewVerdict.PASS, List.of(), null);
		this.publisher.suspend(storyId);

		this.mvc.perform(appeal(storyId, REASON)).andExpect(status().isAccepted());
		assertThat(this.appeals.findAll()).hasSize(2);
	}

	/**
	 * <b>내려가지 않은 작품은 대상이 아니다</b> (§13-59).
	 *
	 * <p>{@code 400} 이 아닌 것은 의도다 — 요청의 형태가 아니라 <b>작품이 놓인 자리</b>가 맞지
	 * 않으며, 그 자리는 서버가 정한다.
	 */
	@Test
	void S13_59_an_approved_story_is_not_appealable() throws Exception {
		UUID storyId = givenApprovedStory();

		this.mvc.perform(appeal(storyId, REASON))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("STORY_NOT_SUSPENDED"));
	}

	/** <b>남의 작품은 없는 것과 구분되지 않는다</b> (I-8). */
	@Test
	void I8_another_authors_story_is_not_found() throws Exception {
		UUID storyId = givenSuspendedStory();

		this.mvc.perform(post("/api/v1/stories/%s/appeal".formatted(storyId))
						.with(asPlayer(UUID.randomUUID())).contentType(MediaType.APPLICATION_JSON)
						.content(JSON.writeValueAsString(Map.of("reason", REASON))))
				.andExpect(status().isNotFound());

		assertThat(this.appeals.findAll()).isEmpty();
	}

	/**
	 * <b>사유 없는 요청은 받지 않는다</b> (§13-59).
	 *
	 * <p>정지된 작품은 이미 큐에 있으므로, 사유가 없으면 이 경로는 <b>검수자에게 아무것도 주지
	 * 않는 버튼</b>이다.
	 */
	@Test
	void S13_59_an_appeal_without_a_reason_is_a_validation_error() throws Exception {
		UUID storyId = givenSuspendedStory();

		this.mvc.perform(appeal(storyId, "   ")).andExpect(status().isBadRequest());
		assertThat(this.appeals.findAll()).isEmpty();
	}

	private MockHttpServletRequestBuilder appeal(UUID storyId, String reason) {
		return post("/api/v1/stories/%s/appeal".formatted(storyId)).with(asPlayer())
				.contentType(MediaType.APPLICATION_JSON)
				.content(JSON.writeValueAsString(Map.of("reason", reason)));
	}

	/** 큐가 이 작품에 붙인 표시. <b>검수자가 보는 것</b>이 곧 이 값이다. */
	private boolean appealedFlagOf(UUID storyId) {
		return this.queue.pending().stream().filter(item -> item.storyId().equals(storyId))
				.findFirst().orElseThrow(() -> new AssertionError("정지된 작품이 큐에 없다 (§13-41)"))
				.appealed();
	}

	/** 신고 누적이 내린 자리와 같은 상태를 만든다 (R8.9) — 정지는 가시성을 지우지 않는다. */
	private UUID givenSuspendedStory() throws Exception {
		UUID storyId = givenApprovedStory();
		this.publisher.suspend(storyId);
		return storyId;
	}

	/** 자동 검수만으로 승인되는 가시성으로 낸다. */
	private UUID givenApprovedStory() throws Exception {
		UUID draftId = givenDraft();
		String body = this.mvc.perform(submit(draftId))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.reviewStatus").value("approved"))
				.andReturn().getResponse().getContentAsString();
		return UUID.fromString(JSON.readTree(body).get("storyId").asString());
	}

	private MockHttpServletRequestBuilder submit(UUID draftId) {
		return post("/api/v1/authoring/drafts/%s/submit".formatted(draftId)).with(asPlayer())
				.contentType(MediaType.APPLICATION_JSON).content("{\"visibility\":\"UNLISTED\"}");
	}

	private UUID givenDraft() throws Exception {
		String created = this.mvc.perform(post("/api/v1/authoring/drafts").with(asPlayer()))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		UUID draftId = UUID.fromString(JSON.readTree(created).get("draftId").asString());

		this.mvc.perform(patch("/api/v1/authoring/drafts/%s".formatted(draftId)).with(asPlayer())
						.contentType(MediaType.APPLICATION_JSON)
						.content(JSON.writeValueAsString(Map.of("step", 5, "payload", PAYLOAD))))
				.andExpect(status().isOk());
		return draftId;
	}

	private String column(UUID storyId, String name) {
		return JdbcClient.create(this.catalog).sql("SELECT " + name + "::text FROM story WHERE id = ?")
				.param(storyId).query(String.class).optional().orElse(null);
	}
}

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
import com.neowadaeum.authoring.review.StoryReviewRepository;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * #245 — <b>계약에 있던 가시성 변경이 실제 요청 경로에서 동작한다</b> (§13.8, R8.6, B-55).
 *
 * <p><b>여기서 지키는 것은 두 문장이다.</b> {@code public} 은 사람이 연다 (R8.6). 그리고
 * <b>반려는 승격을 거절하는 일이지 이미 가진 것을 빼앗는 일이 아니다</b> (§13-42).
 *
 * <p><b>S-11 — 픽스처는 전부 가상의 문자열이다.</b>
 */
class StoryVisibilityApiIntegrationTests extends ContainerTestBase {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String PAYLOAD = "{\"title\":\"봄의 학교\",\"shortDesc\":\"소개\","
			+ "\"worldIntro\":\"소개\",\"worldPrompt\":\"봄의 학교에서 시작한다.\","
			+ "\"chapters\":[{\"title\":\"1장\",\"summarySeed\":\"시작\"}],"
			+ "\"endings\":[{\"label\":\"좋은 끝\",\"epilogueText\":\"잘 끝났다.\"}]}";

	private static final UUID REVIEWER_REF = UUID.fromString("00000000-0000-4000-8000-0000000000e1");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ReviewQueueService queue;

	@Autowired
	private StoryDraftRepository drafts;

	@Autowired
	private StoryReviewRepository reviews;

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
	}

	/**
	 * <b>{@code unlisted} → {@code public} 은 사람을 부른다</b> (R8.6, B-55 DoD).
	 *
	 * <p>이 경로가 없으면 <b>자동 검수만 뚫고 공개 섹션에 오르는 길</b>이 열린다.
	 */
	@Test
	void R8_6_promoting_an_unlisted_story_to_public_opens_a_human_review() throws Exception {
		UUID storyId = givenApprovedStory("UNLISTED");

		this.mvc.perform(change(storyId, "PUBLIC"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reviewStatus").value("in_review"))
				.andExpect(jsonPath("$.visibility").value("unlisted"));

		assertThat(this.queue.pending()).extracting(ReviewQueueService.QueueItem::storyId)
				.contains(storyId);
	}

	/**
	 * <b>승격은 가시성을 지우지 않는다.</b>
	 *
	 * <p>검수 중 노출은 R2.3 이 닫는다 — 타인 조회 조건이 {@code approved} <b>AND</b>
	 * {@code visibility <> private} 이므로 {@code in_review} 하나로 이미 가려진다. 함께
	 * {@code private} 로 내리면 반려된 작성자가 <b>원래 갖고 있던 공개까지</b> 잃는다.
	 */
	@Test
	void R2_3_a_promotion_keeps_the_visibility_it_already_had() throws Exception {
		UUID storyId = givenApprovedStory("UNLISTED");

		this.mvc.perform(change(storyId, "PUBLIC")).andExpect(status().isOk());

		assertThat(column(storyId, "review_status")).isEqualTo("in_review");
		assertThat(column(storyId, "visibility")).isEqualTo("unlisted");
	}

	/** <b>통과가 {@code public} 을 연다</b> (§13-39). */
	@Test
	void R8_6_passing_a_promotion_opens_public() throws Exception {
		UUID storyId = givenApprovedStory("UNLISTED");
		this.mvc.perform(change(storyId, "PUBLIC")).andExpect(status().isOk());

		var decision = this.queue.decide(REVIEWER_REF, storyId, ReviewVerdict.PASS, List.of(), null);

		assertThat(decision.reviewStatus().columnValue()).isEqualTo("approved");
		assertThat(column(storyId, "visibility")).isEqualTo("public");
	}

	/**
	 * <b>반려는 있던 자리로 돌려놓는다</b> (§13-42).
	 *
	 * <p>작성자가 이미 갖고 있던 {@code unlisted} 게시는 승격 요청과 무관하게 승인된 것이다.
	 * 승격을 거절하면서 그것까지 내리면 <b>요청한 것보다 많은 것을 잃는다</b> — 그러면 아무도
	 * 승격을 요청하지 않게 되고, 그 길을 막은 것과 같아진다.
	 */
	@Test
	void R8_6_rejecting_a_promotion_returns_the_story_to_where_it_was() throws Exception {
		UUID storyId = givenApprovedStory("UNLISTED");
		this.mvc.perform(change(storyId, "PUBLIC")).andExpect(status().isOk());

		var decision = this.queue.decide(REVIEWER_REF, storyId, ReviewVerdict.REJECT, List.of(), null);

		assertThat(decision.reviewStatus().columnValue()).isEqualTo("approved");
		assertThat(column(storyId, "review_status")).isEqualTo("approved");
		assertThat(column(storyId, "visibility")).isEqualTo("unlisted");
	}

	/**
	 * <b>첫 제출의 반려는 그대로다</b> (I-8).
	 *
	 * <p>승격 반려가 돌아갈 자리를 갖는 것은 <b>돌아갈 자리가 있기 때문</b>이다. 처음 낸 작품은
	 * 아무에게도 보인 적이 없으므로 반려는 여전히 숨김이다.
	 */
	@Test
	void I8_rejecting_a_first_submission_still_hides_the_story() throws Exception {
		UUID draftId = givenDraft();
		this.mvc.perform(submit(draftId, "PUBLIC")).andExpect(status().isAccepted());
		UUID storyId = this.reviews.findAll().getFirst().getStoryId();

		this.queue.decide(REVIEWER_REF, storyId, ReviewVerdict.REJECT, List.of(), null);

		assertThat(column(storyId, "review_status")).isEqualTo("rejected");
		assertThat(column(storyId, "visibility")).isEqualTo("private");
	}

	/**
	 * <b>{@code private} 에서는 올라올 수 없다</b> (§13-39).
	 *
	 * <p>아무에게도 보인 적 없는 작품을 공개하는 것은 승격이 아니라 <b>제출</b>이고, 그 길은
	 * {@code submit} 에 이미 있다. 두 경로가 같은 상태를 서로 다른 뜻으로 쓰면 반려가 어디로
	 * 돌아가야 하는지 알 수 없어진다.
	 */
	@Test
	void R8_6_a_private_story_is_published_by_submitting_not_by_promoting() throws Exception {
		UUID storyId = givenApprovedStory("PRIVATE");

		this.mvc.perform(change(storyId, "PUBLIC")).andExpect(status().isBadRequest());
	}

	/** <b>좁히는 방향에는 사람이 필요 없다</b> (R8.6) — 볼 수 있는 사람이 줄어들 뿐이다. */
	@Test
	void R8_6_narrowing_the_visibility_needs_no_human() throws Exception {
		UUID storyId = givenApprovedStory("UNLISTED");

		this.mvc.perform(change(storyId, "PRIVATE"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reviewStatus").value("approved"))
				.andExpect(jsonPath("$.visibility").value("private"));
	}

	/** <b>검수를 기다리는 작품은 작성자가 움직일 수 없다</b> — 그러면 판정을 작성자가 되돌린다. */
	@Test
	void I8_a_story_that_is_not_approved_cannot_change_its_visibility() throws Exception {
		UUID storyId = givenApprovedStory("UNLISTED");
		this.mvc.perform(change(storyId, "PUBLIC")).andExpect(status().isOk());

		this.mvc.perform(change(storyId, "PRIVATE")).andExpect(status().isBadRequest());
	}

	/** <b>남의 작품은 없는 것과 구분되지 않는다</b> (I-8). */
	@Test
	void I8_another_authors_story_is_not_found() throws Exception {
		UUID storyId = givenApprovedStory("UNLISTED");

		this.mvc.perform(patch("/api/v1/stories/%s/visibility".formatted(storyId))
						.with(asPlayer(UUID.randomUUID())).contentType(MediaType.APPLICATION_JSON)
						.content("{\"visibility\":\"PRIVATE\"}"))
				.andExpect(status().isNotFound());
	}

	/** 알 수 없는 가시성은 검증에서 걸린다 (§9.1). */
	@Test
	void R8_6_an_unknown_visibility_is_a_validation_error() throws Exception {
		UUID storyId = givenApprovedStory("UNLISTED");

		this.mvc.perform(change(storyId, "EVERYONE")).andExpect(status().isBadRequest());
	}

	private MockHttpServletRequestBuilder change(UUID storyId, String visibility) {
		return patch("/api/v1/stories/%s/visibility".formatted(storyId)).with(asPlayer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"visibility\":\"%s\"}".formatted(visibility));
	}

	private MockHttpServletRequestBuilder submit(UUID draftId, String visibility) {
		return post("/api/v1/authoring/drafts/%s/submit".formatted(draftId)).with(asPlayer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"visibility\":\"%s\"}".formatted(visibility));
	}

	/** 자동 검수만으로 승인되는 가시성으로 낸다 — 승격의 출발점이다. */
	private UUID givenApprovedStory(String visibility) throws Exception {
		UUID draftId = givenDraft();
		String body = this.mvc.perform(submit(draftId, visibility))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.reviewStatus").value("approved"))
				.andReturn().getResponse().getContentAsString();
		return UUID.fromString(JSON.readTree(body).get("storyId").asString());
	}

	private UUID givenDraft() throws Exception {
		String created = this.mvc.perform(post("/api/v1/authoring/drafts").with(asPlayer()))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		UUID draftId = UUID.fromString(JSON.readTree(created).get("draftId").asString());

		this.mvc.perform(patch("/api/v1/authoring/drafts/%s".formatted(draftId)).with(asPlayer())
						.contentType(MediaType.APPLICATION_JSON)
						.content(JSON.writeValueAsString(java.util.Map.of("step", 5, "payload", PAYLOAD))))
				.andExpect(status().isOk());
		return draftId;
	}

	private String column(UUID storyId, String name) {
		return JdbcClient.create(this.catalog).sql("SELECT " + name + "::text FROM story WHERE id = ?")
				.param(storyId).query(String.class).optional().orElse(null);
	}
}

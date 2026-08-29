package com.neowadaeum.authoring.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.authoring.blocklist.BlocklistEntryRepository;
import com.neowadaeum.authoring.blocklist.BlocklistTeardown;
import com.neowadaeum.authoring.blocklist.PersistentBlocklistQuery;
import com.neowadaeum.authoring.draft.StoryDraftRepository;
import com.neowadaeum.authoring.review.StoryReviewRepository;
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
 * B-54 — 제출이 <b>실제 요청 경로에서</b> 동작한다 (§13.8, R8.6).
 */
class SubmissionApiIntegrationTests extends ContainerTestBase {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String PAYLOAD = "{\"title\":\"봄의 학교\",\"shortDesc\":\"소개\","
			+ "\"worldIntro\":\"소개\",\"worldPrompt\":\"봄의 학교에서 시작한다.\","
			+ "\"chapters\":[{\"title\":\"1장\",\"summarySeed\":\"시작\"}],"
			+ "\"endings\":[{\"label\":\"좋은 끝\",\"epilogueText\":\"잘 끝났다.\"}]}";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StoryDraftRepository drafts;

	@Autowired
	private StoryReviewRepository reviews;

	@Autowired
	private BlocklistEntryRepository blocklist;

	@Autowired
	private PersistentBlocklistQuery blocklistCache;

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
		BlocklistTeardown.clear(this.blocklist, this.blocklistCache);
	}

	/** 제출은 <b>접수</b>다 — 자동 승인이든 인간 검수 대기든 같은 코드로 답한다. */
	@Test
	void R8_6_a_submission_is_accepted() throws Exception {
		UUID draftId = givenDraft();

		this.mvc.perform(submit(draftId, "UNLISTED"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.reviewStatus").value("approved"))
				.andExpect(jsonPath("$.visibility").value("unlisted"));
	}

	/** <b>{@code public} 은 사람을 기다리고 그동안 {@code private} 이다</b> (R8.6). */
	@Test
	void R8_6_a_public_submission_waits_and_stays_private() throws Exception {
		UUID draftId = givenDraft();

		this.mvc.perform(submit(draftId, "PUBLIC"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.reviewStatus").value("in_review"))
				.andExpect(jsonPath("$.visibility").value("private"));
	}

	/** <b>제출한 적이 없으면 {@code draft} 다</b> — 404 로 답하면 원고가 사라졌다고 읽는다. */
	@Test
	void R8_6_an_unsubmitted_draft_reports_draft() throws Exception {
		UUID draftId = givenDraft();

		this.mvc.perform(get("/api/v1/authoring/drafts/%s/review".formatted(draftId)).with(asPlayer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reviewStatus").value("draft"))
				.andExpect(jsonPath("$.storyId").doesNotExist());
	}

	/** 제출한 뒤에는 그 상태가 다시 읽힌다 — 화면이 새로 열려도 어디까지 왔는지 안다. */
	@Test
	void R8_6_the_status_is_readable_after_submitting() throws Exception {
		UUID draftId = givenDraft();
		this.mvc.perform(submit(draftId, "PUBLIC")).andExpect(status().isAccepted());

		this.mvc.perform(get("/api/v1/authoring/drafts/%s/review".formatted(draftId)).with(asPlayer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reviewStatus").value("in_review"))
				.andExpect(jsonPath("$.storyId").isNotEmpty());
	}

	/** <b>남의 원고는 없는 것과 구분되지 않는다</b> (I-8). */
	@Test
	void I8_another_authors_draft_cannot_be_submitted() throws Exception {
		UUID draftId = givenDraft();

		this.mvc.perform(post("/api/v1/authoring/drafts/%s/submit".formatted(draftId))
						.with(asPlayer(UUID.randomUUID())).contentType(MediaType.APPLICATION_JSON)
						.content("{\"visibility\":\"UNLISTED\"}"))
				.andExpect(status().isNotFound());
	}

	/** 알 수 없는 가시성은 검증에서 걸린다 (§9.1). */
	@Test
	void R8_6_an_unknown_visibility_is_a_validation_error() throws Exception {
		UUID draftId = givenDraft();

		this.mvc.perform(submit(draftId, "EVERYONE")).andExpect(status().isBadRequest());
	}

	private MockHttpServletRequestBuilder submit(UUID draftId, String visibility) {
		return post("/api/v1/authoring/drafts/%s/submit".formatted(draftId)).with(asPlayer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"visibility\":\"%s\"}".formatted(visibility));
	}

	private UUID givenDraft() throws Exception {
		String created = this.mvc.perform(post("/api/v1/authoring/drafts").with(asPlayer()))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		UUID draftId = UUID.fromString(JSON.readTree(created).get("draftId").asString());

		this.mvc.perform(patch("/api/v1/authoring/drafts/%s".formatted(draftId)).with(asPlayer())
						.contentType(MediaType.APPLICATION_JSON)
						.content(JSON.writeValueAsString(
								java.util.Map.of("step", 5, "payload", PAYLOAD))))
				.andExpect(status().isOk());
		return draftId;
	}
}

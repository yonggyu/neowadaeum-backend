package com.neowadaeum.authoring.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.authoring.draft.StoryDraftRepository;
import com.neowadaeum.authoring.review.ReviewQueueService;
import com.neowadaeum.authoring.review.ReviewVerdict;
import com.neowadaeum.authoring.review.StoryReviewRepository;
import com.neowadaeum.catalog.query.StoryCatalogFacade;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * #290-3 — <b>게시된 작품을 지우는 경로</b> (§13-58).
 *
 * <p><b>여기서 지키는 것은 세 문장이다.</b> 지운 작품은 어디에도 없다 (I-8). 되돌아오지 않는다.
 * 그리고 <b>플레이한 사람들의 기록은 남는다</b> (§13-44 와 같은 근거).
 *
 * <p><b>S-11 — 픽스처는 전부 가상의 문자열이다.</b>
 */
class StoryDeletionApiIntegrationTests extends ContainerTestBase {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String PAYLOAD = "{\"title\":\"여름의 정거장\",\"shortDesc\":\"소개\","
			+ "\"worldIntro\":\"소개\",\"worldPrompt\":\"여름의 정거장에서 시작한다.\","
			+ "\"chapters\":[{\"title\":\"1장\",\"summarySeed\":\"시작\"}],"
			+ "\"endings\":[{\"label\":\"좋은 끝\",\"epilogueText\":\"잘 끝났다.\"}]}";

	private static final UUID REVIEWER_REF = UUID.fromString("00000000-0000-4000-8000-0000000000e2");

	private static final UUID STRANGER_REF = UUID.fromString("00000000-0000-4000-8000-0000000000e3");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ReviewQueueService queue;

	@Autowired
	private StoryDraftRepository drafts;

	@Autowired
	private StoryReviewRepository reviews;

	@Autowired
	private StoryCatalogFacade catalogQueries;

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
	 * <b>지우면 작성자의 목록에서 사라진다</b> (§13-58).
	 *
	 * <p>"내 작품"은 노출 조건을 걸지 않는 유일한 목록이다 — 걸러 주지 않으면 <b>작성자에게만
	 * 삭제가 취소된 것처럼</b> 보인다.
	 */
	@Test
	void S13_58_a_deleted_story_leaves_the_authors_list() throws Exception {
		UUID storyId = givenApprovedStory("UNLISTED");
		assertThat(myStoryIds()).contains(storyId.toString());

		this.mvc.perform(delete(path(storyId)).with(asPlayer())).andExpect(status().isNoContent());

		assertThat(myStoryIds()).doesNotContain(storyId.toString());
	}

	/** <b>상세도 함께 닫힌다</b> (I-8) — 목록에서만 가리면 id 를 아는 사람이 그대로 읽는다. */
	@Test
	void I8_a_deleted_story_is_not_readable_by_anyone() throws Exception {
		UUID storyId = givenApprovedStory("UNLISTED");
		this.mvc.perform(get(path(storyId)).with(asPlayer(STRANGER_REF))).andExpect(status().isOk());

		this.mvc.perform(delete(path(storyId)).with(asPlayer())).andExpect(status().isNoContent());

		this.mvc.perform(get(path(storyId)).with(asPlayer(STRANGER_REF)))
				.andExpect(status().isNotFound());
		this.mvc.perform(get(path(storyId)).with(asPlayer())).andExpect(status().isNotFound());
	}

	/**
	 * <b>남의 작품은 없는 것과 구분되지 않는다</b> (I-8).
	 *
	 * <p>{@code storyId} 는 누구나 들고 있는 값이다. 권한 없음과 존재하지 않음을 다른 코드로
	 * 답하면 그 하나로 <b>작품의 존재를 물을 수 있다.</b>
	 */
	@Test
	void I8_another_authors_story_cannot_be_deleted_and_is_not_found() throws Exception {
		UUID storyId = givenApprovedStory("UNLISTED");

		this.mvc.perform(delete(path(storyId)).with(asPlayer(STRANGER_REF)))
				.andExpect(status().isNotFound());

		assertThat(column(storyId, "review_status")).isEqualTo("approved");
	}

	/** <b>두 번째 삭제는 없는 작품이다</b> (§13-58) — 되돌아보는 경로도 남기지 않는다. */
	@Test
	void S13_58_deleting_an_already_deleted_story_is_not_found() throws Exception {
		UUID storyId = givenApprovedStory("UNLISTED");
		this.mvc.perform(delete(path(storyId)).with(asPlayer())).andExpect(status().isNoContent());

		this.mvc.perform(delete(path(storyId)).with(asPlayer())).andExpect(status().isNotFound());
	}

	/**
	 * <b>가시성 변경으로 되돌릴 수 없다</b> (§13-58).
	 *
	 * <p>이것이 {@code deleted} 를 {@code visibility} 가 아니라 {@code review_status} 에 둔
	 * 이유다 — 가시성에 두었다면 이 요청 하나가 삭제를 취소한다.
	 */
	@Test
	void S13_58_a_deleted_story_cannot_come_back_through_the_visibility_path() throws Exception {
		UUID storyId = givenApprovedStory("UNLISTED");
		this.mvc.perform(delete(path(storyId)).with(asPlayer())).andExpect(status().isNoContent());

		this.mvc.perform(patch(path(storyId) + "/visibility").with(asPlayer())
						.contentType(MediaType.APPLICATION_JSON).content("{\"visibility\":\"PUBLIC\"}"))
				.andExpect(status().isNotFound());

		assertThat(column(storyId, "review_status")).isEqualTo("deleted");
	}

	/**
	 * <b>나중에 도착한 검수 판정이 되살리지 못한다</b> (§13-58).
	 *
	 * <p>검수자가 큐를 연 뒤 작성자가 지우는 순서는 실제로 일어난다. 조회를 막는 것만으로는
	 * 부족하고, 쓰기 자체가 {@code deleted} 행을 건드리지 않아야 한다.
	 */
	@Test
	void S13_58_a_review_verdict_does_not_resurrect_a_deleted_story() throws Exception {
		UUID storyId = givenApprovedStory("UNLISTED");
		this.mvc.perform(patch(path(storyId) + "/visibility").with(asPlayer())
						.contentType(MediaType.APPLICATION_JSON).content("{\"visibility\":\"PUBLIC\"}"))
				.andExpect(status().isOk());
		this.mvc.perform(delete(path(storyId)).with(asPlayer())).andExpect(status().isNoContent());

		assertThat(this.queue.pending()).extracting(ReviewQueueService.QueueItem::storyId)
				.doesNotContain(storyId);
		try {
			this.queue.decide(REVIEWER_REF, storyId, ReviewVerdict.PASS, List.of(), null);
		}
		catch (RuntimeException expected) {
			// 판정이 거절되는 쪽이 정상이다. 통과하더라도 상태는 바뀌지 않아야 하므로 아래를 본다.
		}
		assertThat(column(storyId, "review_status")).isEqualTo("deleted");
	}

	/**
	 * <b>진행 중 세션은 사라진 작품과 같은 답을 받는다</b> (§13-58).
	 *
	 * <p>{@code play} 는 이 조회 하나로 판정한다 — 비어 있으면 {@code STORY_SUSPENDED}(423,
	 * 읽기 전용)이고, 그 자리는 정지된 작품과 <b>이미 공유되어 있다.</b> 그래서 새 에러 코드를
	 * 만들지 않았다.
	 */
	@Test
	void S13_58_a_running_session_sees_a_deleted_story_as_gone() throws Exception {
		UUID storyId = givenApprovedStory("UNLISTED");
		assertThat(this.catalogQueries.status(storyId)).isPresent();

		this.mvc.perform(delete(path(storyId)).with(asPlayer())).andExpect(status().isNoContent());

		assertThat(this.catalogQueries.status(storyId)).isEmpty();
	}

	/**
	 * <b>기록은 남는다</b> (§13-44 와 같은 근거).
	 *
	 * <p>버전·챕터·엔딩은 세션이 고정한 것이고(I-4), 검수 이력은 사람이 판정한 사실이다 —
	 * 둘 다 작성자의 것이 아니다. 행을 지우면 그 위에 매달린 기록이 <b>어디도 가리키지 않는
	 * 값</b>이 된다 (스키마 간 FK 가 없으므로 DB 가 막아 주지도 않는다, §5.3).
	 */
	@Test
	void S13_44_deleting_a_story_keeps_what_players_and_reviewers_left() throws Exception {
		UUID storyId = givenApprovedStory("UNLISTED");
		long reviewsBefore = this.reviews.findAll().stream()
				.filter(review -> review.getStoryId().equals(storyId)).count();

		this.mvc.perform(delete(path(storyId)).with(asPlayer())).andExpect(status().isNoContent());

		assertThat(count("SELECT COUNT(*) FROM story WHERE id = ?", storyId)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM story_version WHERE story_id = ?", storyId))
				.isGreaterThan(0);
		assertThat(count("SELECT COUNT(*) FROM ending_def WHERE story_id = ?", storyId))
				.isGreaterThan(0);
		assertThat(this.reviews.findAll().stream()
				.filter(review -> review.getStoryId().equals(storyId)).count()).isEqualTo(reviewsBefore);
	}

	/**
	 * <b>원고는 지우지 않았고, 그 원고의 다음 제출은 새 작품이다</b> (§13-58).
	 *
	 * <p>없는 작품에 버전을 얹으면 아무도 가리키지 않는 버전만 쌓인다. 작성자가 잃는 것은
	 * 없다 — 원고는 그대로이며, 다시 내면 새 작품으로 선다.
	 */
	@Test
	void S13_58_resubmitting_a_draft_whose_story_was_deleted_creates_a_new_story() throws Exception {
		UUID draftId = givenDraft();
		UUID storyId = submitted(draftId, "UNLISTED");
		this.mvc.perform(delete(path(storyId)).with(asPlayer())).andExpect(status().isNoContent());

		this.mvc.perform(get("/api/v1/authoring/drafts/%s/review".formatted(draftId)).with(asPlayer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reviewStatus").value("draft"));

		UUID republished = submitted(draftId, "UNLISTED");
		assertThat(republished).isNotEqualTo(storyId);
		assertThat(column(storyId, "review_status")).isEqualTo("deleted");
	}

	private static String path(UUID storyId) {
		return "/api/v1/stories/%s".formatted(storyId);
	}

	private List<String> myStoryIds() throws Exception {
		String body = this.mvc.perform(get("/api/v1/me/stories").with(asPlayer()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JSON.readTree(body).get("items").valueStream().map(item -> item.get("storyId").asString())
				.toList();
	}

	/** 자동 검수만으로 승인되는 가시성으로 낸다 — 지울 대상이 게시돼 있어야 한다. */
	private UUID givenApprovedStory(String visibility) throws Exception {
		return submitted(givenDraft(), visibility);
	}

	private UUID submitted(UUID draftId, String visibility) throws Exception {
		String body = this.mvc
				.perform(post("/api/v1/authoring/drafts/%s/submit".formatted(draftId)).with(asPlayer())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"visibility\":\"%s\"}".formatted(visibility)))
				.andExpect(status().isAccepted())
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

	private long count(String sql, UUID storyId) {
		return JdbcClient.create(this.catalog).sql(sql).param(storyId).query(Long.class).single();
	}

	private String column(UUID storyId, String name) {
		return JdbcClient.create(this.catalog).sql("SELECT " + name + "::text FROM story WHERE id = ?")
				.param(storyId).query(String.class).optional().orElse(null);
	}
}

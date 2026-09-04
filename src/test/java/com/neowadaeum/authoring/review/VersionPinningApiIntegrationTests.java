package com.neowadaeum.authoring.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.authoring.draft.DraftService;
import com.neowadaeum.authoring.draft.StoryDraftRepository;
import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.repository.PlaySessionRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * §10.1-12 — <b>진행 중 세션이 새 버전에 영향받지 않는다</b> (R2.1, R8.8, I-4, B-56).
 *
 * <p>여기서만 확인할 수 있는 것: 작성자가 작품을 고쳐 다시 낸 뒤에도 <b>플레이 중인 사람이
 * 보던 버전이 그대로인지</b>, 그리고 Resume 이 그 사실을 <b>알려 주는지</b>.
 *
 * <p><b>S-11 — 픽스처는 전부 가상의 문자열이다.</b>
 */
class VersionPinningApiIntegrationTests extends ContainerTestBase {

	private static final String PAYLOAD = """
			{"title":"봄의 학교","shortDescription":"짧은 소개","worldIntro":"소개",
			 "settingDetail":"봄의 학교에서 시작한다.",
			 "chapters":[{"title":"1장","summarySeed":"시작"}],
			 "endings":[{"label":"좋은 끝","epilogueText":"잘 끝났다."}]}
			""";

	private static final UUID PLAYER_REF = UUID.fromString("00000000-0000-4000-8000-0000000000f1");

	@Autowired
	private org.springframework.test.web.servlet.MockMvc mvc;

	@Autowired
	private SubmissionService submissions;

	@Autowired
	private DraftService drafts;

	@Autowired
	private StoryDraftRepository draftRows;

	@Autowired
	private StoryReviewRepository reviews;

	@Autowired
	private PlaySessionRepository sessions;

	@Autowired
	@Qualifier("catalogDataSource")
	private DataSource catalog;

	private final List<UUID> stories = new java.util.ArrayList<>();

	@AfterEach
	void clear() {
		this.sessions.findAll().stream().filter(s -> PLAYER_REF.equals(s.getPlayerRef()))
				.forEach(this.sessions::delete);
		JdbcClient jdbc = JdbcClient.create(this.catalog);
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
	}

	/**
	 * <b>고정한 버전은 움직이지 않는다</b> (I-4).
	 *
	 * <p>여기서 버전이 따라 움직이면 작성자가 엔딩 조건을 고치는 순간 <b>플레이 중인 모든
	 * 사람의 이야기가 바뀐다.</b>
	 */
	@Test
	void S10_1_12_a_running_session_keeps_its_version_after_a_revision() {
		UUID draftId = givenDraft();
		UUID authorRef = authorOf(draftId);
		UUID storyId = submit(authorRef, draftId);
		UUID pinned = currentVersionOf(storyId);
		UUID sessionId = givenSessionOn(storyId, pinned);

		submit(authorRef, draftId);

		assertThat(this.sessions.findById(sessionId)).get()
				.extracting(PlaySession::getStoryVersionId).isEqualTo(pinned);
		assertThat(currentVersionOf(storyId)).isNotEqualTo(pinned);
	}

	/**
	 * <b>Resume 이 그 사실을 알려 준다</b> (R2.1).
	 *
	 * <p>조용히 옛 버전을 이어가게 두면 사용자는 <b>자기가 보는 것이 최신이 아니라는 것</b>을
	 * 끝내 알지 못한다.
	 */
	@Test
	void R2_1_resume_reports_version_changed_after_a_revision() throws Exception {
		UUID draftId = givenDraft();
		UUID authorRef = authorOf(draftId);
		UUID storyId = submit(authorRef, draftId);
		UUID sessionId = givenSessionOn(storyId, currentVersionOf(storyId));

		submit(authorRef, draftId);

		this.mvc.perform(get("/api/v1/sessions/%s/resume".formatted(sessionId)).with(asPlayer(PLAYER_REF)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sessionState").value("version_changed"));
	}

	/** 개정 전에는 이어갈 수 있다 — 위 테스트가 <b>무엇을 바꿨는지</b>를 이것이 고정한다. */
	@Test
	void R2_1_resume_is_valid_before_a_revision() throws Exception {
		UUID draftId = givenDraft();
		UUID storyId = submit(authorOf(draftId), draftId);
		UUID sessionId = givenSessionOn(storyId, currentVersionOf(storyId));

		this.mvc.perform(get("/api/v1/sessions/%s/resume".formatted(sessionId)).with(asPlayer(PLAYER_REF)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sessionState").value("valid"));
	}

	private UUID givenSessionOn(UUID storyId, UUID versionId) {
		return this.sessions.saveAndFlush(PlaySession.start(PLAYER_REF, storyId, versionId, "fixed",
				"scenario", false, Instant.now())).getId();
	}

	private UUID submit(UUID authorRef, UUID draftId) {
		UUID storyId = this.submissions.submit(authorRef, draftId, Visibility.UNLISTED).storyId();
		if (!this.stories.contains(storyId)) {
			this.stories.add(storyId);
		}
		return storyId;
	}

	private UUID givenDraft() {
		UUID authorRef = UUID.randomUUID();
		UUID draftId = this.drafts.create(authorRef).getId();
		this.drafts.save(authorRef, draftId, 5, PAYLOAD);
		return draftId;
	}

	private UUID authorOf(UUID draftId) {
		return this.draftRows.findById(draftId).orElseThrow().getAuthorRef();
	}

	private UUID currentVersionOf(UUID storyId) {
		return JdbcClient.create(this.catalog)
				.sql("SELECT current_version_id FROM story WHERE id = ?").param(storyId)
				.query(UUID.class).optional().orElse(null);
	}
}

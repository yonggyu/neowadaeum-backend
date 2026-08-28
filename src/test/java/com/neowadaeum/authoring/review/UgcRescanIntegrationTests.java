package com.neowadaeum.authoring.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.authoring.blocklist.BlocklistAdminService;
import com.neowadaeum.authoring.blocklist.BlocklistEntryRepository;
import com.neowadaeum.authoring.blocklist.BlocklistKind;
import com.neowadaeum.authoring.blocklist.BlocklistSeverity;
import com.neowadaeum.authoring.draft.DraftService;
import com.neowadaeum.authoring.draft.StoryDraftRepository;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * B-59(1/2) — <b>승인은 끝이 아니다</b> (R9.4).
 *
 * <p>블록리스트는 운영 중에 늘어난다. 갱신이 앞으로 만들어질 것에만 적용되면 <b>이미 게시된
 * 것은 영원히 옛 기준</b>으로 남는다.
 *
 * <p><b>S-11 — 픽스처는 전부 가상의 문자열이다.</b>
 */
class UgcRescanIntegrationTests extends ContainerTestBase {

	/** 가상의 이름. 실제 블록리스트 항목이 아니다 (S-11). */
	private static final String FICTIONAL = "이나린";

	private static final String CLEAN_PAYLOAD = """
			{"title":"봄의 학교","shortDesc":"짧은 소개","worldIntro":"소개",
			 "worldPrompt":"봄의 학교에서 시작한다.",
			 "chapters":[{"title":"1장","summarySeed":"시작"}],
			 "endings":[{"label":"좋은 끝","epilogueText":"잘 끝났다."}]}
			""";

	@Autowired
	private UgcRescanner rescanner;

	@Autowired
	private ReviewQueueService queue;

	@Autowired
	private SubmissionService submissions;

	@Autowired
	private DraftService drafts;

	@Autowired
	private StoryDraftRepository draftRows;

	@Autowired
	private StoryReviewRepository reviews;

	@Autowired
	private BlocklistAdminService blocklist;

	@Autowired
	private BlocklistEntryRepository blocklistRows;

	@Autowired
	@Qualifier("catalogDataSource")
	private DataSource catalog;

	private final List<UUID> stories = new java.util.ArrayList<>();

	@AfterEach
	void clear() {
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
		this.blocklistRows.deleteAll();
	}

	/**
	 * <b>어제 통과한 것이 오늘 걸린다</b> (R9.4).
	 *
	 * <p>제출 시점에는 목록에 없던 항목이므로 통과했다. 목록이 늘어난 뒤 다시 보면 걸린다.
	 */
	@Test
	void R9_4_a_blocklist_update_catches_an_already_approved_story() {
		UUID storyId = givenApprovedStory(CLEAN_PAYLOAD.replace("봄의 학교에서 시작한다.",
				FICTIONAL + " 이 나온다."));
		this.blocklist.register(BlocklistKind.REAL_PERSON, FICTIONAL, BlocklistSeverity.BLOCK, "test");

		int suspended = this.rescanner.rescan();

		assertThat(suspended).isEqualTo(1);
		assertThat(column(storyId, "review_status")).isEqualTo("suspended");
	}

	/** 걸린 작품은 <b>검수 큐에 오른다</b> — 자동으로 내리되 자동으로 올리지 않는다. */
	@Test
	void R9_4_a_rescanned_story_reaches_the_review_queue() {
		UUID storyId = givenApprovedStory(CLEAN_PAYLOAD.replace("좋은 끝", FICTIONAL + " 의 끝"));
		this.blocklist.register(BlocklistKind.REAL_PERSON, FICTIONAL, BlocklistSeverity.BLOCK, "test");

		this.rescanner.rescan();

		assertThat(this.queue.pending()).anySatisfy(item -> {
			assertThat(item.storyId()).isEqualTo(storyId);
			assertThat(item.reviewStatus()).isEqualTo(ReviewStatus.SUSPENDED);
		});
	}

	/**
	 * <b>이력에 카테고리만 남는다</b> (R8.7, S-11).
	 *
	 * <p>어떤 항목에 걸렸는지를 담으면 그 표가 우회 사전이 된다.
	 */
	@Test
	void R8_7_the_rescan_records_categories_but_not_entries() {
		UUID storyId = givenApprovedStory(CLEAN_PAYLOAD.replace("봄의 학교에서 시작한다.",
				FICTIONAL + " 이 나온다."));
		this.blocklist.register(BlocklistKind.REAL_PERSON, FICTIONAL, BlocklistSeverity.BLOCK, "test");

		this.rescanner.rescan();

		assertThat(this.reviews.findFirstByStoryIdOrderByReviewedAtDesc(storyId)).get()
				.satisfies(review -> {
					assertThat(review.getStage()).isEqualTo(ReviewStage.AUTO);
					assertThat(review.getVerdict()).isEqualTo(ReviewVerdict.REJECT);
					assertThat(review.getReasons()).contains("real_person_harm").doesNotContain(FICTIONAL);
				});
	}

	/** <b>챕터와 엔딩도 대상이다</b> (R8.5) — 세계관만 보면 다른 자리에 넣으면 통과한다. */
	@Test
	void R8_5_chapters_and_endings_are_rescanned_too() {
		UUID storyId = givenApprovedStory(CLEAN_PAYLOAD.replace("\"summarySeed\":\"시작\"",
				"\"summarySeed\":\"" + FICTIONAL + " 이 나온다\""));
		this.blocklist.register(BlocklistKind.REAL_PERSON, FICTIONAL, BlocklistSeverity.BLOCK, "test");

		this.rescanner.rescan();

		assertThat(column(storyId, "review_status")).isEqualTo("suspended");
	}

	/** 걸리지 않은 작품은 그대로다 — 이력도 늘지 않는다. */
	@Test
	void R9_4_a_clean_story_is_left_alone() {
		UUID storyId = givenApprovedStory(CLEAN_PAYLOAD);
		this.blocklist.register(BlocklistKind.REAL_PERSON, FICTIONAL, BlocklistSeverity.BLOCK, "test");
		long before = this.reviews.findByStoryIdOrderByReviewedAtDesc(storyId).size();

		int suspended = this.rescanner.rescan();

		assertThat(suspended).isZero();
		assertThat(column(storyId, "review_status")).isEqualTo("approved");
		assertThat(this.reviews.findByStoryIdOrderByReviewedAtDesc(storyId)).hasSize((int) before);
	}

	/**
	 * <b>두 번 돌아도 한 번만 센다.</b>
	 *
	 * <p>이미 내려간 작품은 승인작이 아니므로 다음 회차의 대상이 아니다 — 회차마다 같은 작품을
	 * 다시 세면 <b>지표가 실제로 일어난 일과 달라진다.</b>
	 */
	@Test
	void R9_4_a_second_pass_does_not_count_the_same_story_again() {
		givenApprovedStory(CLEAN_PAYLOAD.replace("봄의 학교에서 시작한다.", FICTIONAL + " 이 나온다."));
		this.blocklist.register(BlocklistKind.REAL_PERSON, FICTIONAL, BlocklistSeverity.BLOCK, "test");
		assertThat(this.rescanner.rescan()).isEqualTo(1);

		assertThat(this.rescanner.rescan()).isZero();
	}

	/** 블록리스트가 비어 있으면 아무것도 걸리지 않는다 — 재스캔이 스스로 판단하지 않는다. */
	@Test
	void R9_4_an_empty_blocklist_suspends_nothing() {
		UUID storyId = givenApprovedStory(CLEAN_PAYLOAD.replace("봄의 학교에서 시작한다.",
				FICTIONAL + " 이 나온다."));

		assertThat(this.rescanner.rescan()).isZero();
		assertThat(column(storyId, "review_status")).isEqualTo("approved");
	}

	private UUID givenApprovedStory(String payload) {
		UUID authorRef = UUID.randomUUID();
		UUID draftId = this.drafts.create(authorRef).getId();
		this.drafts.save(authorRef, draftId, 5, payload);
		UUID storyId = this.submissions.submit(authorRef, draftId, Visibility.UNLISTED).storyId();
		this.stories.add(storyId);
		return storyId;
	}

	private String column(UUID storyId, String name) {
		return JdbcClient.create(this.catalog).sql("SELECT " + name + "::text FROM story WHERE id = ?")
				.param(storyId).query(String.class).optional().orElse(null);
	}
}

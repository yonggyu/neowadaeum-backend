package com.neowadaeum.authoring.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.authoring.draft.DraftService;
import com.neowadaeum.authoring.draft.StoryDraftRepository;
import com.neowadaeum.authoring.review.ReviewQueueService;
import com.neowadaeum.authoring.review.ReviewStatus;
import com.neowadaeum.authoring.review.ReviewVerdict;
import com.neowadaeum.authoring.review.StoryReviewRepository;
import com.neowadaeum.authoring.review.SubmissionService;
import com.neowadaeum.authoring.review.Visibility;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * B-57 — <b>한 사람이 혼자 작품을 내릴 수 없다</b> (R8.9, 세이프티 L3).
 *
 * <p><b>S-11 — 임계값을 테스트 이름에도 적지 않는다.</b> 값은 여기서 <b>설정하는 것</b>이며,
 * 확인하는 것은 "설정한 만큼 모이면 내려간다"이지 그 값이 얼마인지가 아니다.
 */
class ReportServiceTests extends ContainerTestBase {

	private static final String PAYLOAD = """
			{"title":"봄의 학교","shortDesc":"짧은 소개","worldIntro":"소개",
			 "worldPrompt":"봄의 학교에서 시작한다.",
			 "chapters":[{"title":"1장","summarySeed":"시작"}],
			 "endings":[{"label":"좋은 끝","epilogueText":"잘 끝났다."}]}
			""";

	@Autowired
	private ReportService service;

	@Autowired
	private ContentReportRepository reports;

	@Autowired
	private SubmissionService submissions;

	@Autowired
	private ReviewQueueService queue;

	@Autowired
	private DraftService drafts;

	@Autowired
	private StoryDraftRepository draftRows;

	@Autowired
	private StoryReviewRepository reviews;

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

	/** 신고가 기록된다 — L3 는 여기서 시작한다. */
	@Test
	void R8_9_a_report_is_recorded() {
		UUID storyId = givenApprovedStory();

		this.service.report(UUID.randomUUID(), ReportTarget.STORY, storyId, null, null,
				ReportReason.INAPPROPRIATE, "이유");

		assertThat(this.reports.countByTargetTypeAndTargetId("story", storyId)).isEqualTo(1);
	}

	/**
	 * <b>같은 사람이 같은 대상을 두 번 신고해도 한 건이다</b> (R8.9).
	 *
	 * <p>중복이 세어지면 <b>한 사람이 혼자 작품을 내릴 수 있다.</b>
	 */
	@Test
	void R8_9_the_same_reporter_counts_once() {
		UUID storyId = givenApprovedStory();
		UUID reporter = UUID.randomUUID();
		this.service.report(reporter, ReportTarget.STORY, storyId, null, null, ReportReason.OTHER, null);

		assertThatThrownBy(() -> this.service.report(reporter, ReportTarget.STORY, storyId, null, null,
				ReportReason.OTHER, null))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.ALREADY_EXISTS);

		assertThat(this.reports.countByTargetTypeAndTargetId("story", storyId)).isEqualTo(1);
	}

	/** <b>임계에 닿으면 내려가고 검수 큐에 오른다</b> (R8.9). */
	@Test
	void R8_9_reaching_the_threshold_suspends_the_story_and_queues_it() {
		UUID storyId = givenApprovedStory();
		givenThreshold(2);

		this.service.report(UUID.randomUUID(), ReportTarget.STORY, storyId, null, null,
				ReportReason.INAPPROPRIATE, null);
		var second = this.service.report(UUID.randomUUID(), ReportTarget.STORY, storyId, null, null,
				ReportReason.INAPPROPRIATE, null);

		assertThat(second.suspended()).isTrue();
		assertThat(column(storyId, "review_status")).isEqualTo("suspended");
		assertThat(this.queue.pending()).anySatisfy(item -> {
			assertThat(item.storyId()).isEqualTo(storyId);
			assertThat(item.reviewStatus()).isEqualTo(ReviewStatus.SUSPENDED);
		});
	}

	/** 임계 아래에서는 아무 일도 일어나지 않는다 — 신고 하나가 판정이 되면 안 된다. */
	@Test
	void R8_9_below_the_threshold_nothing_happens() {
		UUID storyId = givenApprovedStory();
		givenThreshold(3);

		var receipt = this.service.report(UUID.randomUUID(), ReportTarget.STORY, storyId, null, null,
				ReportReason.INAPPROPRIATE, null);

		assertThat(receipt.suspended()).isFalse();
		assertThat(column(storyId, "review_status")).isEqualTo("approved");
	}

	/**
	 * <b>임계가 설정되지 않았으면 내리지 않는다</b> (§13-41).
	 *
	 * <p>임의의 기본값을 코드에 두면 그 값이 곧 정책이 되고, 아무도 그것을 정한 적이 없다.
	 */
	@Test
	void R8_9_without_a_configured_threshold_nothing_is_suspended() {
		UUID storyId = givenApprovedStory();

		for (int i = 0; i < 5; i++) {
			this.service.report(UUID.randomUUID(), ReportTarget.STORY, storyId, null, null,
					ReportReason.OTHER, null);
		}

		assertThat(column(storyId, "review_status")).isEqualTo("approved");
	}

	/**
	 * <b>턴 신고는 작품을 내리지 않는다</b> (§13-41).
	 *
	 * <p>세 사람이 서로 다른 턴을 신고한 것과 세 사람이 <b>그 작품</b>을 신고한 것은 다른
	 * 사실이다 — AI 가 한 번 어긋난 것으로 작품이 내려가면 안 된다.
	 */
	@Test
	void R8_9_turn_reports_do_not_suspend_a_story() {
		UUID storyId = givenApprovedStory();
		givenThreshold(1);
		UUID turnId = UUID.randomUUID();

		var receipt = this.service.report(UUID.randomUUID(), ReportTarget.TURN, turnId,
				UUID.randomUUID(), 3, ReportReason.INAPPROPRIATE, null);

		assertThat(receipt.suspended()).isFalse();
		assertThat(column(storyId, "review_status")).isEqualTo("approved");
		assertThat(this.reports.countByTargetTypeAndTargetId("turn", turnId)).isEqualTo(1);
	}

	/**
	 * <b>정지된 작품을 통과시키면 내려가기 전의 가시성으로 돌아간다</b> (§13-41).
	 *
	 * <p>신고 하나로 내려간 {@code unlisted} 작품이 복귀하면서 공개되면 그것은 복귀가 아니다.
	 */
	@Test
	void R8_9_restoring_a_suspended_story_keeps_its_visibility() {
		UUID storyId = givenApprovedStory();
		givenThreshold(1);
		this.service.report(UUID.randomUUID(), ReportTarget.STORY, storyId, null, null,
				ReportReason.OTHER, null);

		var decision = this.queue.decide(UUID.randomUUID(), storyId, ReviewVerdict.PASS, List.of(), null);

		assertThat(decision.reviewStatus()).isEqualTo(ReviewStatus.APPROVED);
		assertThat(column(storyId, "review_status")).isEqualTo("approved");
		assertThat(column(storyId, "visibility")).isEqualTo("unlisted");
	}

	/** 정지된 작품에 {@code hold} 하면 <b>정지 상태 그대로</b> 남는다 — 판정을 미룬 것이다. */
	@Test
	void R8_9_holding_a_suspended_story_leaves_it_suspended() {
		UUID storyId = givenApprovedStory();
		givenThreshold(1);
		this.service.report(UUID.randomUUID(), ReportTarget.STORY, storyId, null, null,
				ReportReason.OTHER, null);

		var decision = this.queue.decide(UUID.randomUUID(), storyId, ReviewVerdict.HOLD, List.of(), null);

		assertThat(decision.reviewStatus()).isEqualTo(ReviewStatus.SUSPENDED);
		assertThat(column(storyId, "review_status")).isEqualTo("suspended");
	}

	/** 신고가 계속 들어와도 <b>상태는 한 번만 바뀐다.</b> */
	@Test
	void R8_9_an_already_suspended_story_is_not_suspended_again() {
		UUID storyId = givenApprovedStory();
		givenThreshold(1);
		this.service.report(UUID.randomUUID(), ReportTarget.STORY, storyId, null, null,
				ReportReason.OTHER, null);

		var later = this.service.report(UUID.randomUUID(), ReportTarget.STORY, storyId, null, null,
				ReportReason.OTHER, null);

		assertThat(later.suspended()).isFalse();
		assertThat(column(storyId, "review_status")).isEqualTo("suspended");
	}

	/** 없는 작품은 신고할 수 없다 — 받아 두면 아무도 볼 수 없는 행이 쌓인다. */
	@Test
	void R8_9_an_unknown_story_cannot_be_reported() {
		assertThatThrownBy(() -> this.service.report(UUID.randomUUID(), ReportTarget.STORY,
				UUID.randomUUID(), null, null, ReportReason.OTHER, null))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.NOT_FOUND);
	}

	/** 공개 범위마다 다른 임계를 본다 (§13-12) — 사전 검수가 약한 쪽을 사후에 더 본다. */
	@Test
	void R8_9_the_threshold_is_read_per_visibility() {
		UUID storyId = givenApprovedStory();
		// unlisted 에는 임계가 없고 public 에만 있다 — 이 작품은 unlisted 이므로 내려가지 않는다.
		givenThresholdJson("{\"public\":1}");

		var receipt = this.service.report(UUID.randomUUID(), ReportTarget.STORY, storyId, null, null,
				ReportReason.OTHER, null);

		assertThat(receipt.suspended()).isFalse();
	}

	private void givenThreshold(int reports) {
		givenThresholdJson("{\"unlisted\":%d,\"public\":%d}".formatted(reports, reports));
	}

	private void givenThresholdJson(String json) {
		JdbcClient.create(this.catalog).sql("""
						INSERT INTO service_config (config_key, config_value) VALUES (?, ?::jsonb)
						ON CONFLICT (config_key) DO UPDATE SET config_value = EXCLUDED.config_value
						""")
				.params(SuspensionThresholds.CONFIG_KEY, json).update();
	}

	private UUID givenApprovedStory() {
		UUID authorRef = UUID.randomUUID();
		UUID draftId = this.drafts.create(authorRef).getId();
		this.drafts.save(authorRef, draftId, 5, PAYLOAD);
		UUID storyId = this.submissions.submit(authorRef, draftId, Visibility.UNLISTED).storyId();
		this.stories.add(storyId);
		return storyId;
	}

	private String column(UUID storyId, String name) {
		return JdbcClient.create(this.catalog).sql("SELECT " + name + "::text FROM story WHERE id = ?")
				.param(storyId).query(String.class).optional().orElse(null);
	}
}

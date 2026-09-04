package com.neowadaeum.authoring.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.ContainerTestBase;
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
 * B-59(2/2) — <b>뽑히는 것과 내려가는 것은 다르다</b> (R8.11, §13-12, §13-42).
 *
 * <p>무작위로 뽑혔다는 것은 <b>아무 근거도 아니다.</b> 그것으로 게시된 작품을 내리면 검수가
 * 아니라 처벌이다.
 *
 * <p><b>비율은 여기서 설정한다.</b> 확인하는 것은 "설정한 대로 뽑힌다"이지 그 값이 얼마인지가
 * 아니다 (S-11).
 */
class UgcReviewSamplingIntegrationTests extends ContainerTestBase {

	private static final String PAYLOAD = """
			{"title":"봄의 학교","shortDescription":"짧은 소개","worldIntro":"소개",
			 "settingDetail":"봄의 학교에서 시작한다.",
			 "chapters":[{"title":"1장","summarySeed":"시작"}],
			 "endings":[{"label":"좋은 끝","epilogueText":"잘 끝났다."}]}
			""";

	@Autowired
	private UgcReviewSampler sampler;

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
		jdbc.sql("DELETE FROM service_config WHERE config_key = ?").param(SamplingRates.CONFIG_KEY)
				.update();
	}

	/**
	 * <b>뽑혀도 내려가지 않는다</b> (§13-12, §13-42).
	 *
	 * <p>이것이 이 작업 전체의 요점이다 — 무작위로 뽑혔다는 것은 아무 근거도 아니다.
	 */
	@Test
	void R8_11_a_sampled_story_is_not_suspended() {
		UUID storyId = givenApprovedStory();
		givenEveryStorySampled();

		this.sampler.sample();

		assertThat(column(storyId, "review_status")).isEqualTo("approved");
		assertThat(column(storyId, "visibility")).isEqualTo("unlisted");
	}

	/** 뽑힌 작품은 <b>승인 상태 그대로</b> 검수 큐에 오른다 (R8.11). */
	@Test
	void R8_11_a_sampled_story_reaches_the_queue_while_still_approved() {
		UUID storyId = givenApprovedStory();
		givenEveryStorySampled();

		int flagged = this.sampler.sample();

		assertThat(flagged).isEqualTo(1);
		assertThat(this.queue.pending()).anySatisfy(item -> {
			assertThat(item.storyId()).isEqualTo(storyId);
			assertThat(item.reviewStatus()).isEqualTo(ReviewStatus.APPROVED);
		});
	}

	/**
	 * <b>비율이 설정되지 않았으면 아무것도 뽑지 않는다</b> (§13-42).
	 *
	 * <p>임의의 기본 비율을 코드에 두면 그 값이 곧 정책이 되고, 검수 큐가 조용히 차오른다.
	 */
	@Test
	void R8_11_without_a_configured_rate_nothing_is_sampled() {
		UUID storyId = givenApprovedStory();

		assertThat(this.sampler.sample()).isZero();
		assertThat(this.queue.pending()).noneSatisfy(
				item -> assertThat(item.storyId()).isEqualTo(storyId));
	}

	/** 범위를 벗어난 값은 <b>비율 없음</b>이다 — 파싱 실패를 정책으로 바꾸지 않는다. */
	@Test
	void R8_11_an_out_of_range_rate_samples_nothing() {
		givenApprovedStory();
		givenRate("{\"unlisted\":900}");

		assertThat(this.sampler.sample()).isZero();
	}

	/** 공개 범위마다 다른 비율을 본다 (§13-12) — 이 작품은 {@code unlisted} 다. */
	@Test
	void R8_11_the_rate_is_read_per_visibility() {
		givenApprovedStory();
		givenRate("{\"public\":100}");

		assertThat(this.sampler.sample()).isZero();
	}

	/**
	 * <b>이미 올라가 있는 작품은 다시 올리지 않는다.</b>
	 *
	 * <p>회차마다 표식이 쌓이면 큐에는 한 번 뜨지만 이력이 계속 길어지고, 그 이력은 <b>왜
	 * 그렇게 됐는지</b>가 아니라 배치 실행 기록이 된다.
	 */
	@Test
	void R8_11_a_second_pass_does_not_flag_the_same_story_again() {
		givenApprovedStory();
		givenEveryStorySampled();
		assertThat(this.sampler.sample()).isEqualTo(1);

		assertThat(this.sampler.sample()).isZero();
	}

	/**
	 * <b>통과하면 있던 자리로 돌아간다</b> (§13-42).
	 *
	 * <p>무작위로 뽑혔을 뿐인 작품이 통과하면서 공개되면 그것은 복귀가 아니다.
	 */
	@Test
	void R8_11_passing_a_sampled_story_keeps_its_visibility() {
		UUID storyId = givenApprovedStory();
		givenEveryStorySampled();
		this.sampler.sample();

		var decision = this.queue.decide(UUID.randomUUID(), storyId, ReviewVerdict.PASS, List.of(), null);

		assertThat(decision.reviewStatus()).isEqualTo(ReviewStatus.APPROVED);
		assertThat(column(storyId, "visibility")).isEqualTo("unlisted");
	}

	/**
	 * <b>사람이 보면 큐에서 빠진다.</b>
	 *
	 * <p>표식을 지우는 경로가 따로 없다 — 인간 이력이 얹혀 표식이 더 이상 최신이 아니게 된다.
	 */
	@Test
	void R8_11_a_human_verdict_takes_the_story_out_of_the_queue() {
		UUID storyId = givenApprovedStory();
		givenEveryStorySampled();
		this.sampler.sample();

		this.queue.decide(UUID.randomUUID(), storyId, ReviewVerdict.PASS, List.of(), null);

		assertThat(this.queue.pending()).noneSatisfy(
				item -> assertThat(item.storyId()).isEqualTo(storyId));
	}

	/** 반려하면 내려간다 — 사람이 본 뒤라면 그것은 근거가 있다. */
	@Test
	void R8_11_rejecting_a_sampled_story_hides_it() {
		UUID storyId = givenApprovedStory();
		givenEveryStorySampled();
		this.sampler.sample();

		this.queue.decide(UUID.randomUUID(), storyId, ReviewVerdict.REJECT,
				List.of(com.neowadaeum.common.spi.SafetyCategory.RATING_EXCEEDED), null);

		assertThat(column(storyId, "review_status")).isEqualTo("rejected");
		assertThat(column(storyId, "visibility")).isEqualTo("private");
	}

	/** 비율 100 이면 전부 뽑힌다 — 경계에 예외를 두지 않았다는 것을 고정한다. */
	private void givenEveryStorySampled() {
		givenRate("{\"unlisted\":100,\"public\":100}");
	}

	private void givenRate(String json) {
		JdbcClient.create(this.catalog).sql("""
						INSERT INTO service_config (config_key, config_value) VALUES (?, ?::jsonb)
						ON CONFLICT (config_key) DO UPDATE SET config_value = EXCLUDED.config_value
						""")
				.params(SamplingRates.CONFIG_KEY, json).update();
	}

	private UUID givenApprovedStory() {
		UUID authorRef = UUID.randomUUID();
		UUID draftId = this.drafts.create(authorRef).getId();
		this.drafts.save(authorRef, draftId, 5, PAYLOAD);
		var outcome = this.submissions.submit(authorRef, draftId, Visibility.UNLISTED);
		assertThat(outcome.reviewStatus())
				.as("픽스처가 자동 검수에서 걸렸다 — 블록리스트가 비어 있어야 한다")
				.isEqualTo(ReviewStatus.APPROVED);
		this.stories.add(outcome.storyId());
		return outcome.storyId();
	}

	private String column(UUID storyId, String name) {
		return JdbcClient.create(this.catalog).sql("SELECT " + name + "::text FROM story WHERE id = ?")
				.param(storyId).query(String.class).optional().orElse(null);
	}
}

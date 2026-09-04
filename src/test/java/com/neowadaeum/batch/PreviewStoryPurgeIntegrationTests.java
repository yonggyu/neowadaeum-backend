package com.neowadaeum.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.catalog.publish.StoryDefinition;
import com.neowadaeum.catalog.publish.StoryPublisher;
import com.neowadaeum.common.spi.PreviewSessionPurge;
import com.neowadaeum.common.spi.PreviewStoryPurge;
import com.neowadaeum.common.support.RetentionProperties;
import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.repository.PlaySessionRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * B-61(3/3) — <b>미리보기가 쌓아 둔 작품은 사라진다</b> (§13-37, R8.12).
 *
 * <p>미리보기는 부를 때마다 작품을 하나씩 발행한다 (§13-5). 일일 상한은 <b>속도를 늦출 뿐</b>
 * 쌓이는 것을 멈추지 않는다.
 *
 * <p><b>남아야 할 것을 함께 센다.</b> 기간이 지나지 않은 미리보기와 <b>제출된 작품</b>이 그것이다 —
 * 후자를 지우면 그것은 작성자의 작품을 지우는 일이다.
 */
class PreviewStoryPurgeIntegrationTests extends ContainerTestBase {

	private static final String STATE_SCHEMA = "{\"flags\":[]}";

	@Autowired
	private PreviewStoryPurge previewStories;

	@Autowired
	private PreviewSessionPurge previewSessions;

	/** §13-68 (#332) — 검수를 기다리는 원고가 가리키는 미리보기는 이번 회차에서 빠진다. */
	@Autowired
	private com.neowadaeum.common.spi.PreviewRetentionHold hold;

	@Autowired
	private RetentionProperties retention;

	@Autowired
	private StoryPublisher publisher;

	@Autowired
	private PlaySessionRepository sessions;

	@Autowired
	@Qualifier("catalogDataSource")
	private DataSource catalog;

	@Autowired
	@Qualifier("playDataSource")
	private DataSource play;

	private final List<UUID> createdStories = new java.util.ArrayList<>();

	private final List<UUID> createdSessions = new java.util.ArrayList<>();

	private final List<UUID> createdDrafts = new java.util.ArrayList<>();

	/** <b>내가 만든 것만 치운다.</b> 컨테이너는 한 벌이고 시드 작품이 그 안에 있다. */
	@AfterEach
	void clear() {
		this.createdSessions.forEach(this::deleteSession);
		this.createdSessions.clear();
		// 원고가 작품을 가리키므로 원고를 먼저 지운다.
		this.createdDrafts.forEach(id -> catalogJdbc()
				.sql("DELETE FROM story_draft WHERE id = ?").param(id).update());
		this.createdDrafts.clear();
		this.createdStories.forEach(this::deleteStory);
		this.createdStories.clear();
	}

	/** <b>기간이 지난 미리보기는 사라진다</b> — 작품도, 버전에 매달린 것들도. */
	@Test
	void S13_37_an_expired_preview_story_is_gone() {
		UUID storyId = givenPreviewStory(daysAgo(this.retention.previewStoryDays() + 1));

		assertThat(this.previewStories.expiredPreviewStories()).contains(storyId);
		assertThat(this.previewStories.purge(List.of(storyId))).isEqualTo(1);

		assertThat(countCatalog("SELECT count(*) FROM story WHERE id = ?", storyId)).isZero();
		assertThat(countCatalog("SELECT count(*) FROM story_version WHERE story_id = ?", storyId))
				.isZero();
		assertThat(countCatalog("SELECT count(*) FROM chapter_def WHERE story_id = ?", storyId))
				.isZero();
		assertThat(countCatalog("SELECT count(*) FROM ending_def WHERE story_id = ?", storyId))
				.isZero();
	}

	/**
	 * <b>기간이 지나지 않은 미리보기는 남는다.</b>
	 *
	 * <p>이것이 없으면 "전부 지우는 구현"도 통과하고, 그 구현은 <b>작성자가 지금 보고 있는
	 * 미리보기</b>를 지운다.
	 */
	@Test
	void S13_37_a_recent_preview_story_stays() {
		UUID storyId = givenPreviewStory(daysAgo(this.retention.previewStoryDays() - 1));

		assertThat(this.previewStories.expiredPreviewStories()).doesNotContain(storyId);
		assertThat(countCatalog("SELECT count(*) FROM story WHERE id = ?", storyId)).isEqualTo(1);
	}

	/**
	 * <b>제출된 작품은 건드리지 않는다</b> (R8.6).
	 *
	 * <p>검수를 기다리는 작품이 파기되면 작성자는 <b>낸 적 없는 상태</b>로 돌아간다. 판별이
	 * 나이만 보면 그 일이 일어난다 — 제출은 작품을 새로 만들지 않고 <b>상태를 옮기기</b> 때문이다.
	 */
	@Test
	void R8_6_a_submitted_story_is_not_a_preview() {
		UUID storyId = givenPreviewStory(daysAgo(this.retention.previewStoryDays() + 1));
		catalogJdbc().sql("UPDATE story SET review_status = 'in_review' WHERE id = ?")
				.param(storyId).update();

		assertThat(this.previewStories.expiredPreviewStories()).doesNotContain(storyId);
		// 목록을 얻은 뒤에 제출됐어도 지워지지 않는다 — 파기 직전에 조건을 다시 본다.
		assertThat(this.previewStories.purge(List.of(storyId))).isZero();
		assertThat(countCatalog("SELECT count(*) FROM story WHERE id = ?", storyId)).isEqualTo(1);
	}

	/** <b>공식 작품은 대상이 아니다.</b> 시드 작품이 파기되면 서비스가 비어 버린다. */
	@Test
	void S13_37_an_official_story_is_not_a_preview() {
		List<UUID> expired = this.previewStories.expiredPreviewStories();

		assertThat(officialStoryIds()).isNotEmpty()
				.allSatisfy(officialId -> assertThat(expired).doesNotContain(officialId));
	}

	/**
	 * <b>세션도 함께 사라진다.</b>
	 *
	 * <p>작품이 지워진 뒤에 남은 세션은 어느 작품의 것인지 물어볼 곳이 없는 기록이다.
	 */
	@Test
	void S13_37_the_preview_sessions_go_with_the_story() {
		UUID storyId = givenPreviewStory(daysAgo(this.retention.previewStoryDays() + 1));
		UUID sessionId = givenPreviewSession(storyId);

		assertThat(this.previewSessions.purgeByStories(List.of(storyId))).isEqualTo(1);

		assertThat(this.sessions.findById(sessionId)).isEmpty();
	}

	/** 파기는 <b>몇 건인지</b>를 돌려준다 — batch 가 그것만 로그에 남긴다 (§9.4, S-11). */
	@Test
	void S13_37_the_purge_reports_how_many_stories_it_removed() {
		UUID first = givenPreviewStory(daysAgo(this.retention.previewStoryDays() + 1));
		UUID second = givenPreviewStory(daysAgo(this.retention.previewStoryDays() + 1));

		assertThat(this.previewStories.purge(List.of(first, second))).isEqualTo(2);
	}

	// ── 준비 ────────────────────────────────────────────────

	/**
	 * <b>실제 발행 경로로 만든다.</b> 미리보기가 만드는 모양을 손으로 흉내 내면, 그 모양이
	 * 바뀌었을 때 이 테스트만 통과하고 파기는 실패한다.
	 */
	/**
	 * <b>§13-68 (#332) — 검수를 기다리는 미리보기는 남는다.</b>
	 *
	 * <p>30일은 <i>"원고가 남아 있으니 다시 부르면 된다"</i> 를 근거로 정해졌다. 검수자가 그
	 * 턴을 본다면 그 근거는 성립하지 않는다 — 지우고 나면 검수 상세는 조용히 빈 자리를 그리고,
	 * 그 침묵은 <i>이 작품은 아무 문장도 내놓지 않았다</i> 로 읽힌다.
	 */
	@Test
	void S13_68_a_preview_awaiting_a_verdict_is_kept() {
		UUID previewStory = givenPreviewStory(expired());
		givenDraftPointing(previewStory, givenSubmittedStory("in_review"));

		assertThat(this.hold.heldPreviewStories(this.previewStories.expiredPreviewStories()))
				.contains(previewStory);
	}

	/**
	 * <b>유예는 영구가 아니다</b> (§13-68).
	 *
	 * <p>판정이 나면 검수자가 볼 일이 끝났고, 작성자는 언제든 다시 부를 수 있다.
	 */
	@Test
	void S13_68_once_the_verdict_lands_the_preview_is_purgeable_again() {
		UUID previewStory = givenPreviewStory(expired());
		givenDraftPointing(previewStory, givenSubmittedStory("approved"));

		assertThat(this.hold.heldPreviewStories(this.previewStories.expiredPreviewStories()))
				.doesNotContain(previewStory);
	}

	/**
	 * <b>낸 적 없는 원고의 미리보기는 유예하지 않는다</b> (§13-68).
	 *
	 * <p>검수자가 볼 일이 없고, 30일을 정한 근거가 그대로 성립한다 (§13-37).
	 */
	@Test
	void S13_68_a_preview_of_a_draft_never_submitted_is_not_held() {
		UUID previewStory = givenPreviewStory(expired());
		givenDraftPointing(previewStory, null);

		assertThat(this.hold.heldPreviewStories(this.previewStories.expiredPreviewStories()))
				.doesNotContain(previewStory);
	}

	private Instant expired() {
		return Instant.now().minus(this.retention.previewStoryRetention()).minus(1, ChronoUnit.DAYS);
	}

	/** 제출된 작품 — 미리보기와 <b>별개의 작품</b>이다 (§13-5). */
	private UUID givenSubmittedStory(String reviewStatus) {
		UUID storyId = givenPreviewStory(Instant.now());
		catalogJdbc().sql("UPDATE story SET review_status = ?, visibility = 'unlisted' WHERE id = ?")
				.params(reviewStatus, storyId).update();
		return storyId;
	}

	private void givenDraftPointing(UUID previewStoryId, UUID submittedStoryId) {
		UUID draftId = UUID.randomUUID();
		catalogJdbc().sql("""
						INSERT INTO story_draft (id, author_ref, story_id, preview_story_id, step, payload)
						VALUES (?, ?, ?, ?, 5, '{}'::JSONB)
						""")
				.params(draftId, UUID.randomUUID(), submittedStoryId, previewStoryId).update();
		this.createdDrafts.add(draftId);
	}

	private UUID givenPreviewStory(Instant createdAt) {
		StoryPublisher.PublishedVersion published = this.publisher.publishNew(
				new StoryDefinition(UUID.randomUUID(), "미리보기 " + UUID.randomUUID(), "요약",
						"세계관", "world prompt", "flag",
						List.of(new StoryDefinition.Chapter(1, "1장", null, null, 1, 3)),
						List.of(new StoryDefinition.Ending(1, "기본 엔딩", "끝", null, true, false)),
						List.of()),
				STATE_SCHEMA);
		UUID storyId = published.storyId();
		this.createdStories.add(storyId);

		// **발행은 지금 시각을 찍는다** — 오래된 작품을 만들려면 SQL 로 옮겨야 한다.
		catalogJdbc().sql("UPDATE story SET created_at = ? WHERE id = ?")
				.params(createdAt.atOffset(ZoneOffset.UTC), storyId).update();
		return storyId;
	}

	private UUID givenPreviewSession(UUID storyId) {
		UUID versionId = catalogJdbc()
				.sql("SELECT id FROM story_version WHERE story_id = ?")
				.param(storyId).query(UUID.class).single();
		PlaySession session = this.sessions.saveAndFlush(PlaySession.start(UUID.randomUUID(),
				storyId, versionId, "fixed", "scenario", true, Instant.now()));
		this.createdSessions.add(session.getId());
		return session.getId();
	}

	private List<UUID> officialStoryIds() {
		return catalogJdbc().sql("SELECT id FROM story WHERE author_type = 'official'")
				.query(UUID.class).list();
	}

	// ── 뒷정리 · 조회 ────────────────────────────────────────

	private void deleteSession(UUID sessionId) {
		playJdbc().sql("DELETE FROM story_summary WHERE session_id = ?").param(sessionId).update();
		playJdbc().sql("DELETE FROM game_state_snapshot WHERE session_id = ?").param(sessionId)
				.update();
		playJdbc().sql("DELETE FROM turn WHERE session_id = ?").param(sessionId).update();
		playJdbc().sql("DELETE FROM play_session WHERE id = ?").param(sessionId).update();
	}

	private void deleteStory(UUID storyId) {
		catalogJdbc().sql("""
				DELETE FROM character
				WHERE story_version_id IN (SELECT id FROM story_version WHERE story_id = ?)
				""").param(storyId).update();
		catalogJdbc().sql("DELETE FROM chapter_def WHERE story_id = ?").param(storyId).update();
		catalogJdbc().sql("DELETE FROM ending_def WHERE story_id = ?").param(storyId).update();
		catalogJdbc().sql("DELETE FROM ending_stat WHERE story_id = ?").param(storyId).update();
		catalogJdbc().sql("DELETE FROM story_genre WHERE story_id = ?").param(storyId).update();
		catalogJdbc().sql("DELETE FROM story_version WHERE story_id = ?").param(storyId).update();
		catalogJdbc().sql("DELETE FROM story WHERE id = ?").param(storyId).update();
	}

	private long countCatalog(String sql, UUID id) {
		return catalogJdbc().sql(sql).param(id).query(Long.class).single();
	}

	private JdbcClient catalogJdbc() {
		return JdbcClient.create(this.catalog);
	}

	private JdbcClient playJdbc() {
		return JdbcClient.create(this.play);
	}

	private static Instant daysAgo(int days) {
		return Instant.now().minus(days, ChronoUnit.DAYS);
	}
}

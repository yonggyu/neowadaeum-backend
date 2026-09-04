package com.neowadaeum.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.catalog.publish.StoryDefinition;
import com.neowadaeum.catalog.publish.StoryPublisher;
import com.neowadaeum.common.spi.WithdrawnAuthorContent;
import com.neowadaeum.identity.domain.User;
import com.neowadaeum.identity.repository.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * B-62(2/2) — <b>떠난 사람의 작품은 지우지 않고 내린다</b> (R12.5 단서, §13-9).
 *
 * <p>매핑만 끊으면 그 작품은 <b>주인을 알 수 없는 채로 계속 공개된 상태</b>가 된다. 그렇다고
 * 지울 수도 없다 — 작품에 매달린 세션과 도달률은 작성자의 것이 아니라 <b>플레이한 사람들의
 * 것</b>이다.
 */
class WithdrawnAuthorContentIntegrationTests extends ContainerTestBase {

	private static final String STATE_SCHEMA = "{\"flags\":[]}";

	private static final String ANONYMOUS_AUTHOR = "탈퇴한 사용자";

	@Autowired
	private WithdrawnAuthorContent authorContent;

	@Autowired
	private StoryPublisher publisher;

	@Autowired
	private UserRepository users;

	@Autowired
	private RetentionBatch batch;

	@Autowired
	@Qualifier("catalogDataSource")
	private DataSource catalog;

	@Autowired
	@Qualifier("identityDataSource")
	private DataSource identity;

	private final List<UUID> createdStories = new java.util.ArrayList<>();

	private final List<UUID> createdAuthors = new java.util.ArrayList<>();

	private final List<UUID> createdUsers = new java.util.ArrayList<>();

	@AfterEach
	void clear() {
		this.createdStories.forEach(this::deleteStory);
		this.createdStories.clear();
		this.createdAuthors.forEach(authorRef -> {
			catalogJdbc().sql("DELETE FROM story_draft WHERE author_ref = ?").param(authorRef).update();
			catalogJdbc().sql("DELETE FROM author_profile WHERE player_ref = ?").param(authorRef)
					.update();
		});
		this.createdAuthors.clear();
		this.createdUsers.forEach(userId -> identityJdbc()
				.sql("DELETE FROM \"user\" WHERE id = ?").param(userId).update());
		this.createdUsers.clear();
	}

	/**
	 * <b>공개가 멈춘다. 작품은 남는다</b> (§13-9).
	 *
	 * <p>지우면 그 작품을 플레이한 사람들의 기록까지 함께 사라진다.
	 */
	@Test
	void S13_9_a_public_story_is_unlisted_not_deleted() {
		UUID authorRef = givenAuthor();
		UUID storyId = givenPublicStory(authorRef);

		assertThat(this.authorContent.handleWithdrawal(List.of(authorRef))).isEqualTo(1);

		assertThat(visibilityOf(storyId)).isEqualTo("unlisted");
		assertThat(countCatalog("SELECT count(*) FROM story WHERE id = ?", storyId)).isEqualTo(1);
	}

	/** <b>작성자명이 익명이 된다</b> (§13-9). 프로필이 없던 작성자에게도 남긴다. */
	@Test
	void S13_9_the_author_name_becomes_anonymous() {
		UUID authorRef = givenAuthor();
		givenPublicStory(authorRef);

		this.authorContent.handleWithdrawal(List.of(authorRef));

		assertThat(displayNameOf(authorRef)).isEqualTo(ANONYMOUS_AUTHOR);
	}

	/**
	 * <b>검수 상태는 그대로다.</b>
	 *
	 * <p>승인은 작품에 대한 판정이지 작성자에 대한 판정이 아니다. 되돌리면 그 작품은 <b>다시 검수
	 * 큐에 올라</b> 사람의 시간을 쓴다.
	 */
	@Test
	void S13_9_the_review_status_is_untouched() {
		UUID authorRef = givenAuthor();
		UUID storyId = givenPublicStory(authorRef);

		this.authorContent.handleWithdrawal(List.of(authorRef));

		assertThat(reviewStatusOf(storyId)).isEqualTo("approved");
	}

	/**
	 * <b>남의 작품은 그대로 공개다.</b>
	 *
	 * <p>이것이 없으면 "전부 내리는 구현"도 통과하고, 그 구현은 탈퇴 한 건으로 라이브러리를
	 * 비운다.
	 */
	@Test
	void S13_9_another_authors_story_stays_public() {
		UUID withdrawing = givenAuthor();
		UUID staying = givenAuthor();
		UUID theirStory = givenPublicStory(staying);

		this.authorContent.handleWithdrawal(List.of(withdrawing));

		assertThat(visibilityOf(theirStory)).isEqualTo("public");
		assertThat(displayNameOf(staying)).isNull();
	}

	/** 발행되지 않은 원고는 지운다 (R12.4) — 볼 사람이 없어진 비공개 저작물이다. */
	@Test
	void R12_4_the_unpublished_drafts_are_deleted() {
		UUID authorRef = givenAuthor();
		givenDraft(authorRef);

		this.authorContent.handleWithdrawal(List.of(authorRef));

		assertThat(countCatalog("SELECT count(*) FROM story_draft WHERE author_ref = ?", authorRef))
				.isZero();
	}

	/**
	 * <b>배치 한 회차가 순서를 지킨다</b> (R12.5).
	 *
	 * <p>{@code author_ref} 가 곧 {@code playerRef} 다 — 매핑을 먼저 끊으면 <b>어느 작품이 그
	 * 사람의 것인지 알 수 없다.</b> 한 회차를 실제로 돌려 작품이 내려간 <b>뒤에</b> 매핑이
	 * 사라졌는지 본다.
	 */
	@Test
	void R12_5_one_batch_run_unlists_before_the_mapping_is_gone() {
		UUID authorRef = givenAuthor();
		UUID userId = givenWithdrawnMember(authorRef);
		UUID storyId = givenPublicStory(authorRef);

		this.batch.run();

		assertThat(visibilityOf(storyId)).isEqualTo("unlisted");
		assertThat(displayNameOf(authorRef)).isEqualTo(ANONYMOUS_AUTHOR);
		assertThat(this.users.findById(userId)).get()
				.satisfies(user -> assertThat(user.getPlayerRef()).isNull());
	}

	// ── 준비 ────────────────────────────────────────────────

	private UUID givenAuthor() {
		UUID authorRef = UUID.randomUUID();
		this.createdAuthors.add(authorRef);
		return authorRef;
	}

	/** 승인·공개된 UGC. **실제 발행 경로로 만든다** — 모양이 바뀌면 이 테스트도 함께 깨져야 한다. */
	private UUID givenPublicStory(UUID authorRef) {
		StoryPublisher.PublishedVersion published = this.publisher.publishNew(
				new StoryDefinition(authorRef, "작품 " + UUID.randomUUID(), "요약", "세계관",
						"world prompt", "flag",
						List.of(new StoryDefinition.Chapter(1, "1장", null, null, 1, 3)),
						List.of(new StoryDefinition.Ending(1, "기본 엔딩", "끝", null, true, false)),
						List.of(), List.of(), null),
				STATE_SCHEMA);
		UUID storyId = published.storyId();
		this.createdStories.add(storyId);
		this.publisher.applyReview(storyId, "approved", "public");
		return storyId;
	}

	private void givenDraft(UUID authorRef) {
		catalogJdbc().sql("INSERT INTO story_draft (id, author_ref) VALUES (?, ?)")
				.params(UUID.randomUUID(), authorRef).update();
	}

	private UUID givenWithdrawnMember(UUID playerRef) {
		User user = this.users.saveAndFlush(
				User.register(playerRef, LocalDate.of(2000, 1, 1), Instant.now()));
		this.createdUsers.add(user.getId());
		identityJdbc().sql("UPDATE \"user\" SET status = 'withdrawn' WHERE id = ?")
				.param(user.getId()).update();
		return user.getId();
	}

	// ── 뒷정리 · 조회 ────────────────────────────────────────

	private void deleteStory(UUID storyId) {
		catalogJdbc().sql("""
				DELETE FROM character
				WHERE story_version_id IN (SELECT id FROM story_version WHERE story_id = ?)
				""").param(storyId).update();
		catalogJdbc().sql("DELETE FROM chapter_def WHERE story_id = ?").param(storyId).update();
		catalogJdbc().sql("DELETE FROM ending_def WHERE story_id = ?").param(storyId).update();
		catalogJdbc().sql("DELETE FROM ending_stat WHERE story_id = ?").param(storyId).update();
		catalogJdbc().sql("DELETE FROM story_genre WHERE story_id = ?").param(storyId).update();
		catalogJdbc().sql("DELETE FROM story_review WHERE story_id = ?").param(storyId).update();
		catalogJdbc().sql("UPDATE story SET current_version_id = NULL WHERE id = ?").param(storyId)
				.update();
		catalogJdbc().sql("DELETE FROM story_version WHERE story_id = ?").param(storyId).update();
		catalogJdbc().sql("DELETE FROM story WHERE id = ?").param(storyId).update();
	}

	private String visibilityOf(UUID storyId) {
		return catalogJdbc().sql("SELECT visibility FROM story WHERE id = ?").param(storyId)
				.query(String.class).single();
	}

	private String reviewStatusOf(UUID storyId) {
		return catalogJdbc().sql("SELECT review_status FROM story WHERE id = ?").param(storyId)
				.query(String.class).single();
	}

	private String displayNameOf(UUID authorRef) {
		return catalogJdbc().sql("SELECT display_name FROM author_profile WHERE player_ref = ?")
				.param(authorRef).query(String.class).optional().orElse(null);
	}

	private long countCatalog(String sql, UUID id) {
		return catalogJdbc().sql(sql).param(id).query(Long.class).single();
	}

	private JdbcClient catalogJdbc() {
		return JdbcClient.create(this.catalog);
	}

	private JdbcClient identityJdbc() {
		return JdbcClient.create(this.identity);
	}
}

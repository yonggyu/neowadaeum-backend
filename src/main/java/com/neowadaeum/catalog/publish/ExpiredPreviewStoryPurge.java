package com.neowadaeum.catalog.publish;

import com.neowadaeum.common.spi.PreviewStoryPurge;
import com.neowadaeum.common.support.RetentionProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 미리보기가 쌓아 둔 작품의 파기 (§13-37, R8.12, B-61).
 *
 * <p><b>무엇이 미리보기인가</b> — {@code author_type = 'user'} 이면서 {@code private} ·
 * {@code draft} 인 작품이다. 제출 경로는 발행과 <b>같은 트랜잭션 안에서</b> 검수 상태를 옮기므로
 * ({@code SubmissionService}), 이 조합으로 남아 있는 것은 미리보기뿐이다. 공식 작품은
 * {@code author_type} 이 다르고, 검수를 기다리거나 통과한 작품은 {@code review_status} 가 다르다.
 *
 * <p><b>지워도 잃는 것이 없다.</b> 작성자의 원고는 {@code story_draft} 에 그대로 있고 미리보기는
 * 그것의 사본이다 — 다시 보고 싶으면 다시 부르면 된다 (일일 상한 안에서).
 *
 * <p><b>{@link StoryPublisher} 와 같은 {@code JdbcClient} 를 쓴다.</b> catalog 의 쓰기 경로가
 * 그렇게 되어 있고, 지우는 일에 필요한 것은 <b>조건</b>이지 행의 내용이 아니다.
 *
 * <p><b>매달린 것부터 지운다.</b> 캐릭터 · 챕터 · 엔딩은 버전을 FK 로 가리키고 (§13-1) 버전은
 * 작품을 가리킨다 — 순서를 뒤집으면 제약에 걸린다.
 */
@Service
public class ExpiredPreviewStoryPurge implements PreviewStoryPurge {

	/** §13-5 가 미리보기에 쓰는 값. {@link StoryPublisher#publishNew} 가 이 조합으로 만든다. */
	private static final String USER_AUTHOR_TYPE = "user";

	private static final String PRIVATE_VISIBILITY = "private";

	private static final String DRAFT_REVIEW_STATUS = "draft";

	private static final String DELETE_CHARACTERS = """
			DELETE FROM character
			WHERE story_version_id IN (SELECT id FROM story_version WHERE story_id IN (:storyIds))
			""";

	private static final String DELETE_CHAPTERS = """
			DELETE FROM chapter_def
			WHERE story_version_id IN (SELECT id FROM story_version WHERE story_id IN (:storyIds))
			""";

	private static final String DELETE_ENDINGS = """
			DELETE FROM ending_def
			WHERE story_version_id IN (SELECT id FROM story_version WHERE story_id IN (:storyIds))
			""";

	private final JdbcClient jdbc;

	private final RetentionProperties retention;

	private final Clock clock;

	public ExpiredPreviewStoryPurge(@Qualifier("catalogDataSource") DataSource catalogDataSource,
			RetentionProperties retention, Clock clock) {
		this.jdbc = JdbcClient.create(catalogDataSource);
		this.retention = retention;
		this.clock = clock;
	}

	@Override
	@Transactional(value = "catalogTransactionManager", readOnly = true)
	public List<UUID> expiredPreviewStories() {
		Instant createdBefore = Instant.now(this.clock)
				.minus(this.retention.previewStoryRetention());
		return this.jdbc.sql("""
						SELECT id FROM story
						WHERE author_type = :authorType AND visibility = :visibility
							AND review_status = :reviewStatus AND created_at < :createdBefore
						""")
				.params(Map.of("authorType", USER_AUTHOR_TYPE, "visibility", PRIVATE_VISIBILITY,
						"reviewStatus", DRAFT_REVIEW_STATUS,
						"createdBefore", createdBefore.atOffset(ZoneOffset.UTC)))
				.query(UUID.class)
				.list();
	}

	/**
	 * <b>조건을 다시 확인한다.</b> 대상을 고른 시점과 지우는 시점 사이에 작성자가 그 원고를
	 * 제출했을 수 있다 — 그러면 <b>검수를 기다리는 작품을 파기하게 된다.</b>
	 */
	@Override
	@Transactional("catalogTransactionManager")
	public int purge(Collection<UUID> storyIds) {
		if (storyIds.isEmpty()) {
			return 0;
		}
		List<UUID> targets = stillPreview(storyIds);
		if (targets.isEmpty()) {
			return 0;
		}
		// §13-1 — 캐릭터·챕터·엔딩은 작품이 아니라 **버전**에 묶인다.
		delete(DELETE_CHARACTERS, targets);
		delete(DELETE_CHAPTERS, targets);
		delete(DELETE_ENDINGS, targets);
		delete("DELETE FROM ending_stat WHERE story_id IN (:storyIds)", targets);
		delete("DELETE FROM story_genre WHERE story_id IN (:storyIds)", targets);
		delete("DELETE FROM story_version WHERE story_id IN (:storyIds)", targets);
		return this.jdbc.sql("DELETE FROM story WHERE id IN (:storyIds)")
				.param("storyIds", targets).update();
	}

	private List<UUID> stillPreview(Collection<UUID> storyIds) {
		return this.jdbc.sql("""
						SELECT id FROM story
						WHERE id IN (:storyIds) AND author_type = :authorType
							AND visibility = :visibility AND review_status = :reviewStatus
						""")
				.params(Map.of("storyIds", storyIds, "authorType", USER_AUTHOR_TYPE,
						"visibility", PRIVATE_VISIBILITY, "reviewStatus", DRAFT_REVIEW_STATUS))
				.query(UUID.class)
				.list();
	}

	private void delete(String sql, Collection<UUID> storyIds) {
		this.jdbc.sql(sql).param("storyIds", storyIds).update();
	}
}

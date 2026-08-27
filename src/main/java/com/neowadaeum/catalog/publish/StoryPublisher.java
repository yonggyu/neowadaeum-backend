package com.neowadaeum.catalog.publish;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작품과 버전을 발행한다 (B-53, B-56).
 *
 * <p><b>한 트랜잭션이다.</b> 작품·버전·챕터·엔딩이 함께 들어가지 않으면 <b>플레이하다 중간에
 * 멈추는 작품</b>이 남는다.
 *
 * <p><b>JPA 가 아니라 {@link JdbcClient} 다.</b> catalog 의 읽기 경로가 그렇게 되어 있고
 * ({@code catalog :: query}), 쓰기만 엔티티를 도입하면 <b>같은 표를 두 방식으로 다루게 된다</b> —
 * 그러면 어느 쪽이 진실인지가 매번 문제가 된다.
 *
 * <p><b>슬러그는 서버가 만든다.</b> 작성자가 정하게 하면 남의 작품 주소를 선점할 수 있고,
 * 제목에서 만들면 같은 제목이 충돌한다 — id 를 섞어 유일성을 보장한다.
 */
@Service
public class StoryPublisher {

	/** 미리보기가 만드는 작품의 상태. <b>라이브러리에 뜨지 않는다</b> (R2.3, I-8). */
	private static final String DRAFT_REVIEW_STATUS = "draft";

	private static final String PRIVATE_VISIBILITY = "private";

	/** UGC 다. 공식 작품과 섞이지 않는다. */
	private static final String USER_AUTHOR_TYPE = "user";

	/** §2.3 — 카드 레이아웃이 이 길이를 전제한다. */
	private static final int SHORT_DESC_MAX = 40;

	private static final String CHOICE_POLICY = "{\"min\":1,\"max\":4,\"preferred\":3}";

	private final JdbcClient jdbc;

	private final Clock clock;

	public StoryPublisher(@Qualifier("catalogDataSource") DataSource catalogDataSource, Clock clock) {
		this.jdbc = JdbcClient.create(catalogDataSource);
		this.clock = clock;
	}

	/**
	 * 새 작품을 만들고 첫 버전을 발행한다.
	 *
	 * @return 발행된 버전 참조. 세션이 이것에 고정된다 (I-4)
	 */
	@Transactional("catalogTransactionManager")
	public PublishedVersion publishNew(StoryDefinition definition, String stateSchemaJson) {
		Instant now = Instant.now(this.clock);
		UUID storyId = UUID.randomUUID();

		this.jdbc.sql("""
						INSERT INTO story (id, slug, title, short_desc, world_intro, author_type,
								author_ref, visibility, review_status, created_at)
						VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
						""")
				.params(storyId, slugFor(storyId, definition.title()), definition.title(),
						trimmedShortDesc(definition.shortDesc()), definition.worldIntro(),
						USER_AUTHOR_TYPE, definition.authorRef(), PRIVATE_VISIBILITY,
						DRAFT_REVIEW_STATUS, at(now))
				.update();

		UUID versionId = insertVersion(storyId, 1, definition, stateSchemaJson, now);
		return new PublishedVersion(storyId, versionId);
	}

	/**
	 * 이미 있는 작품에 새 버전을 얹는다 (R8.8).
	 *
	 * <p><b>진행 중 세션은 옛 버전을 계속 본다</b> (I-4, R2.1) — 이 메서드는 그 세션을 건드리지
	 * 않는다.
	 */
	@Transactional("catalogTransactionManager")
	public PublishedVersion publishRevision(UUID storyId, StoryDefinition definition,
			String stateSchemaJson) {
		Instant now = Instant.now(this.clock);
		int nextVersionNo = this.jdbc
				.sql("SELECT COALESCE(MAX(version_no), 0) + 1 FROM story_version WHERE story_id = ?")
				.param(storyId).query(Integer.class).single();

		UUID versionId = insertVersion(storyId, nextVersionNo, definition, stateSchemaJson, now);
		return new PublishedVersion(storyId, versionId);
	}

	/**
	 * <b>현재 버전을 가리키게 한다.</b>
	 *
	 * <p>발행과 나눈 것은 의도다 — 검수를 통과하기 전에는 버전이 있어도 <b>현재</b>가 아니다
	 * (R8.8). 미리보기는 이것을 부르지 않는다.
	 */
	@Transactional("catalogTransactionManager")
	public void markCurrent(UUID storyId, UUID versionId) {
		this.jdbc.sql("UPDATE story SET current_version_id = ?, published_at = ? WHERE id = ?")
				.params(versionId, at(Instant.now(this.clock)), storyId).update();
	}

	/**
	 * 검수 결과를 작품에 반영한다 (R8.6, R8.8).
	 *
	 * <p><b>승인이 곧 게시다</b> — 승인하면서 가시성을 함께 정한다. 나누면 <b>승인됐는데 아무도
	 * 볼 수 없는</b> 작품이 남는다.
	 *
	 * <p>발행 시각은 <b>처음 공개될 때만</b> 찍는다 — 재승인 때마다 갱신하면 라이브러리의
	 * "새로 나온 작품"(§13-25)이 옛 작품으로 채워진다.
	 */
	@Transactional("catalogTransactionManager")
	public void applyReview(UUID storyId, String reviewStatus, String visibility) {
		this.jdbc.sql("""
						UPDATE story SET review_status = ?, visibility = ?,
								published_at = COALESCE(published_at, ?)
						WHERE id = ?
						""")
				.params(reviewStatus, visibility, at(Instant.now(this.clock)), storyId)
				.update();
	}

	/**
	 * 작품의 현재 검수 상태 (§13.8).
	 *
	 * <p>작성자가 <b>지금 어디까지 왔는지</b>를 보는 자리다. 값은 작품에 있고, 그 이유는
	 * 검수 이력에 있다 (R8.7).
	 */
	@Transactional(value = "catalogTransactionManager", readOnly = true)
	public StoryStatus statusOf(UUID storyId) {
		return this.jdbc.sql("SELECT review_status, visibility FROM story WHERE id = ?")
				.param(storyId)
				.query((rs, rowNum) -> new StoryStatus(rs.getString("review_status"),
						rs.getString("visibility")))
				.single();
	}

	/** 작품의 상태 한 벌. */
	public record StoryStatus(String reviewStatus, String visibility) {
	}

	private UUID insertVersion(UUID storyId, int versionNo, StoryDefinition definition,
			String stateSchemaJson, Instant now) {
		UUID versionId = UUID.randomUUID();
		this.jdbc.sql("""
						INSERT INTO story_version (id, story_id, version_no, world_prompt, choice_policy,
								state_schema, state_template_key, published_at)
						VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
						""")
				.params(versionId, storyId, versionNo, definition.worldPrompt(), CHOICE_POLICY,
						stateSchemaJson, definition.stateTemplateKey(), at(now))
				.update();

		for (StoryDefinition.Chapter chapter : definition.chapters()) {
			this.jdbc.sql("""
							INSERT INTO chapter_def (id, story_version_id, story_id, chapter_no, title,
									entry_condition, summary_seed, min_turns, max_turns)
							VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
							""")
					.params(UUID.randomUUID(), versionId, storyId, chapter.chapterNo(), chapter.title(),
							chapter.entryConditionJson(), chapter.summarySeed(), chapter.minTurns(),
							chapter.maxTurns())
					.update();
		}
		for (StoryDefinition.Ending ending : definition.endings()) {
			this.jdbc.sql("""
							INSERT INTO ending_def (id, story_version_id, story_id, ending_no, label,
									epilogue_text, condition, is_default, is_secret)
							VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
							""")
					.params(UUID.randomUUID(), versionId, storyId, ending.endingNo(), ending.label(),
							(ending.epilogueText() != null) ? ending.epilogueText() : "",
							// §13-16 — 기본 엔딩은 조건을 갖지 않는다. 조건이 있으면 그것은 폴백이 아니다.
							ending.isDefault() ? null : ending.conditionJson(), ending.isDefault(),
							ending.isSecret())
					.update();
		}
		return versionId;
	}

	/**
	 * JDBC 는 {@link Instant} 를 그대로 바인딩하지 못한다 — 타임존이 없는 값이라 어느 SQL 타입인지
	 * 정해지지 않는다. UTC 로 못박아 넘긴다 (§9.1).
	 */
	private static OffsetDateTime at(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	/** <b>제목만으로 만들지 않는다</b> — 같은 제목이 충돌하고, 작성자가 주소를 선점할 수 있다. */
	private static String slugFor(UUID storyId, String title) {
		String base = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9가-힣]+", "-")
				.replaceAll("(^-|-$)", "");
		String head = base.isBlank() ? "story" : base.substring(0, Math.min(base.length(), 40));
		return head + "-" + storyId.toString().substring(0, 8);
	}

	/** §2.3 — 넘치면 자른다. 저장에서 거절하면 작성자는 <b>왜 실패했는지</b> 알기 어렵다. */
	private static String trimmedShortDesc(String shortDesc) {
		if (shortDesc == null) {
			return null;
		}
		return shortDesc.length() <= SHORT_DESC_MAX ? shortDesc
				: shortDesc.substring(0, SHORT_DESC_MAX);
	}

	/** 발행 결과. 세션은 여기 고정된다 (I-4). */
	public record PublishedVersion(UUID storyId, UUID versionId) {
	}
}

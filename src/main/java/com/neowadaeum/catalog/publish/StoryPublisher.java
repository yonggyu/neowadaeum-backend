package com.neowadaeum.catalog.publish;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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

	/** 사람을 기다리는 작품의 상태 (R8.6). 검수 큐가 보는 것이 이것이다. */
	private static final String IN_REVIEW_STATUS = "in_review";

	/**
	 * 신고 누적으로 내려간 작품 (R8.9, B-57).
	 *
	 * <p><b>이것도 사람을 기다린다.</b> 자동으로 내린 것을 자동으로 올릴 수는 없으므로 — 그러면
	 * 신고가 곧 판정이 된다 — 정지된 작품은 검수 큐에서 사람의 판정을 기다린다.
	 */
	private static final String SUSPENDED_STATUS = "suspended";

	/**
	 * <b>작성자가 지웠다</b> (§13-58, #290).
	 *
	 * <p><b>흡수 상태다.</b> 이 값의 행은 {@link #statusOf} · {@link #ownerStatusOf} 에 보이지
	 * 않고, {@link #applyReview} · {@link #suspend} 는 그 행을 건드리지 않는다 — 그 둘이
	 * 합쳐져 "지운 것은 어떤 경로로도 돌아오지 않는다"가 된다. 조회를 막는 것만으로는
	 * 부족하다: 삭제 직전에 상태를 읽어 둔 검수 판정이 나중에 도착할 수 있다.
	 */
	private static final String DELETED_STATUS = "deleted";

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
		requireDefaultEnding(versionId);
		this.jdbc.sql("UPDATE story SET current_version_id = ?, published_at = ? WHERE id = ?")
				.params(versionId, at(Instant.now(this.clock)), storyId).update();
	}

	/**
	 * <b>폴백 없는 작품은 현재가 되지 않는다</b> (R2.2, R2.11, #54).
	 *
	 * <p>기본 엔딩이 없으면 어떤 조건에도 걸리지 않은 세션이 <b>끝나지 못한다</b> — 무한히
	 * 진행되는 이야기가 된다.
	 *
	 * <p><b>DB 로는 막을 수 없는 절반이다.</b> 상한("2개 이상 금지")은 partial unique index 가
	 * 잡지만, 하한("0개 금지")은 <b>행의 부재</b>라 CHECK 가 볼 수 없다. 트리거로 막으면 엔딩을
	 * 아직 만들지 않은 {@code draft} 라는 <b>정상 상태까지 거부</b>하게 된다 (§13-16).
	 *
	 * <p>그래서 이 자리다 — <b>작품이 플레이 가능해지는 순간</b>이 R2.2 의 방지 목적이 실제로
	 * 의미를 갖는 유일한 시점이다.
	 */
	private void requireDefaultEnding(UUID versionId) {
		Integer count = this.jdbc
				.sql("SELECT COUNT(*) FROM ending_def WHERE story_version_id = ? AND is_default")
				.param(versionId).query(Integer.class).single();
		if (count == null || count < 1) {
			// S-7 — 폴백 부재를 조용히 넘기지 않는다. 사유는 카테고리 수준이다 (S-11).
			throw new ApiException(ErrorCode.VALIDATION_ERROR,
					java.util.Map.of("reason", "missing_default_ending"));
		}
	}

	/**
	 * 검수 결과를 작품에 반영한다 (R8.6, R8.8).
	 *
	 * <p><b>승인이 곧 게시다</b> — 승인하면서 가시성을 함께 정한다. 나누면 <b>승인됐는데 아무도
	 * 볼 수 없는</b> 작품이 남는다.
	 *
	 * <p>발행 시각은 <b>처음 공개될 때만</b> 찍는다 — 재승인 때마다 갱신하면 라이브러리의
	 * "새로 나온 작품"(§13-25)이 옛 작품으로 채워진다.
	 *
	 * <p><b>지워진 작품은 판정으로 되살아나지 않는다</b> (§13-58). 검수자가 큐를 연 뒤 작성자가
	 * 지우면 판정이 나중에 도착한다 — 조건절이 없으면 그 판정이 {@code deleted} 를
	 * {@code approved} 로 덮어쓴다.
	 */
	@Transactional("catalogTransactionManager")
	public void applyReview(UUID storyId, String reviewStatus, String visibility) {
		this.jdbc.sql("""
						UPDATE story SET review_status = ?, visibility = ?,
								published_at = COALESCE(published_at, ?)
						WHERE id = ? AND review_status <> ?
						""")
				.params(reviewStatus, visibility, at(Instant.now(this.clock)), storyId, DELETED_STATUS)
				.update();
	}

	/**
	 * <b>작성자가 지운다</b> (§13-58, #290-3).
	 *
	 * <p><b>행을 지우지 않는다.</b> 작품에는 플레이한 사람들의 기록이 매달려 있고(세션·턴·
	 * 스냅샷·도달률) 그것은 작성자의 것이 아니다 — §13-44 가 탈퇴 파기에 대해 같은 이유로
	 * 도달률을 되돌리지 않기로 한 것과 같은 판단이다.
	 *
	 * <p><b>가시성을 건드리지 않는다.</b> {@code review_status} 하나로 노출이 이미 닫힌다
	 * (R2.3 — 타인 조회는 {@code approved} 를 요구한다). 함께 지우면 이 작품이 어디까지
	 * 공개돼 있었는지가 사라지고, 신고·검수 이력을 나중에 읽는 사람이 그 맥락을 잃는다.
	 *
	 * <p><b>이미 지워진 작품은 다시 지워지지 않는다</b> — 조건절이 그 보장이며,
	 * {@link #ownerStatusOf} 가 이미 그 행을 보지 못하므로 호출부는 여기까지 오지 않는다.
	 *
	 * @return 이 호출이 실제로 내렸으면 {@code true}
	 */
	@Transactional("catalogTransactionManager")
	public boolean delete(UUID storyId) {
		return this.jdbc
				.sql("UPDATE story SET review_status = ? WHERE id = ? AND review_status <> ?")
				.params(DELETED_STATUS, storyId, DELETED_STATUS).update() > 0;
	}

	/**
	 * 작품의 현재 검수 상태 (§13.8).
	 *
	 * <p>작성자가 <b>지금 어디까지 왔는지</b>를 보는 자리다. 값은 작품에 있고, 그 이유는
	 * 검수 이력에 있다 (R8.7).
	 *
	 * <p><b>지워진 작품은 없는 작품이다</b> (§13-58). 상태를 답하면 그 값을 읽은 쪽이 다음
	 * 상태를 계산해 되쓰게 된다 — 제출(재제출)·검수 판정·신고가 전부 이 조회로 시작한다.
	 * 여기서 한 번 가리면 그 셋이 모두 "없는 작품"으로 끝난다.
	 */
	@Transactional(value = "catalogTransactionManager", readOnly = true)
	public Optional<StoryStatus> statusOf(UUID storyId) {
		return this.jdbc
				.sql("SELECT review_status, visibility FROM story WHERE id = ? AND review_status <> ?")
				.params(storyId, DELETED_STATUS)
				.query((rs, rowNum) -> new StoryStatus(rs.getString("review_status"),
						rs.getString("visibility")))
				.optional();
	}

	/**
	 * 작성자와 함께 보는 현재 상태 (#245).
	 *
	 * <p><b>{@link #statusOf} 와 나눈 것은 의도다.</b> 검수자는 <b>누가 썼는지를 보지 않고</b>
	 * 판정하며(B-55), 작성자만 할 수 있는 일은 그 반대로 소유를 먼저 확인해야 한다 — 한 조회가
	 * 둘을 겸하면 검수 경로가 작성자를 알게 된다.
	 *
	 * <p><b>지워진 작품은 작성자에게도 없다</b> (§13-58). 이 조회가 작성자 전용 경로의 입구이므로
	 * (가시성 변경 · 삭제) 여기서 가리면 그 경로들이 전부 {@code NOT_FOUND} 로 끝난다 — 두 번째
	 * 삭제도, 지운 작품의 가시성을 되돌리는 시도도 같은 답을 받는다.
	 */
	@Transactional(value = "catalogTransactionManager", readOnly = true)
	public Optional<OwnedStory> ownerStatusOf(UUID storyId) {
		return this.jdbc.sql("""
						SELECT author_ref, review_status, visibility FROM story
						WHERE id = ? AND review_status <> ?
						""")
				.params(storyId, DELETED_STATUS)
				.query((rs, rowNum) -> new OwnedStory(rs.getObject("author_ref", UUID.class),
						rs.getString("review_status"), rs.getString("visibility")))
				.optional();
	}

	/**
	 * <b>사람을 기다리는 작품들</b> (R8.6, B-55).
	 *
	 * <p>먼저 만들어진 것부터 준다 — 검수는 순서대로 처리하는 일이고, 나중에 온 것이 앞서면
	 * <b>오래 기다린 작성자가 끝없이 밀린다.</b>
	 *
	 * <p><b>큐의 전체 길이를 세지 않는다</b> (S-11). 몇 건이 밀려 있는지는 검수 처리량을
	 * 드러내며, 그 값을 알면 <b>큐가 길 때를 골라 제출할 수 있다.</b>
	 */
	@Transactional(value = "catalogTransactionManager", readOnly = true)
	public List<AwaitingReview> storiesAwaitingReview(int limit) {
		return this.jdbc.sql("""
						SELECT id, title, author_ref, visibility, review_status, created_at
						FROM story
						WHERE review_status IN (?, ?)
						ORDER BY created_at
						LIMIT ?
						""")
				.params(IN_REVIEW_STATUS, SUSPENDED_STATUS, limit)
				.query((rs, rowNum) -> new AwaitingReview(rs.getObject("id", UUID.class),
						rs.getString("title"), rs.getObject("author_ref", UUID.class),
						rs.getString("visibility"), rs.getString("review_status"),
						rs.getTimestamp("created_at").toInstant()))
				.list();
	}

	/**
	 * 이 작성자가 <b>낸</b> 작품 수 (R8.12, B-60).
	 *
	 * <p><b>미리보기가 만든 것은 세지 않는다.</b> 그것은 매 미리보기마다 늘어나고(§13-37)
	 * 파기는 B-61 이 가져간다 — 함께 세면 <b>미리보기 몇 번으로 작품을 못 만들게 된다.</b>
	 * 제출을 지난 작품만 상한의 대상이다.
	 *
	 * <p><b>지운 작품도 세지 않는다</b> (§13-58). 지우고도 자리가 비지 않으면 작성자는 상한에
	 * 닿은 뒤 <b>아무것도 할 수 없다</b> — 지우는 것이 유일하게 남은 선택지인데 그것이 아무것도
	 * 바꾸지 않기 때문이다. 상한은 <b>지금 갖고 있는 작품</b>을 세는 규칙이다 (R8.12).
	 */
	@Transactional(value = "catalogTransactionManager", readOnly = true)
	public long countSubmittedStoriesOf(UUID authorRef) {
		return this.jdbc.sql("""
						SELECT COUNT(*) FROM story
						WHERE author_type = ? AND author_ref = ? AND review_status NOT IN (?, ?)
						""")
				.params(USER_AUTHOR_TYPE, authorRef, DRAFT_REVIEW_STATUS, DELETED_STATUS)
				.query(Long.class).single();
	}

	/**
	 * 지목된 작품들의 큐 표시용 정보 (R8.11, B-59).
	 *
	 * <p>샘플링은 <b>승인 상태 그대로</b> 큐에 올린다 (§13-42) — 그래서 상태로 찾을 수 없고,
	 * 무엇을 올릴지는 {@code authoring} 의 검수 이력이 정한다. 여기는 <b>그 id 들의 제목</b>을
	 * 답할 뿐이다.
	 *
	 * <p><b>지워진 작품은 큐에 남지 않는다</b> (§13-58). 표본으로 뽑힌 뒤 작성자가 지운 작품을
	 * 계속 보여 주면 검수자가 <b>아무에게도 보이지 않는 작품</b>을 읽는 데 시간을 쓴다.
	 */
	@Transactional(value = "catalogTransactionManager", readOnly = true)
	public List<AwaitingReview> storiesByIds(Collection<UUID> storyIds) {
		if (storyIds.isEmpty()) {
			return List.of();
		}
		return this.jdbc.sql("""
						SELECT id, title, author_ref, visibility, review_status, created_at
						FROM story WHERE id IN (:ids) AND review_status <> :deleted ORDER BY created_at
						""")
				.param("ids", storyIds)
				.param("deleted", DELETED_STATUS)
				.query((rs, rowNum) -> new AwaitingReview(rs.getObject("id", UUID.class),
						rs.getString("title"), rs.getObject("author_ref", UUID.class),
						rs.getString("visibility"), rs.getString("review_status"),
						rs.getTimestamp("created_at").toInstant()))
				.list();
	}

	/**
	 * 신고 누적으로 내린다 (R8.9, B-57).
	 *
	 * <p><b>가시성을 건드리지 않는다.</b> 정지는 되돌려질 수 있는 판단이고, 여기서 가시성을
	 * 지워 버리면 <b>사람이 통과시킨 뒤 어디로 돌려놓아야 하는지</b>를 알 수 없다.
	 *
	 * <p><b>이미 내려간 작품은 다시 내리지 않는다.</b> 신고가 계속 들어와도 상태는 한 번만
	 * 바뀐다 — 조건절이 그 보장이다.
	 *
	 * @return 이 호출이 실제로 내렸으면 {@code true}
	 */
	@Transactional("catalogTransactionManager")
	public boolean suspend(UUID storyId) {
		// §13-58 — 지워진 작품은 내릴 것이 없다. 신고 누적은 조회가 아니라 카운트에서 오므로
		// statusOf 가 가려도 여기까지 닿을 수 있다.
		return this.jdbc
				.sql("UPDATE story SET review_status = ? WHERE id = ? AND review_status NOT IN (?, ?)")
				.params(SUSPENDED_STATUS, storyId, SUSPENDED_STATUS, DELETED_STATUS).update() > 0;
	}

	/**
	 * 마지막으로 발행된 버전.
	 *
	 * <p>승인은 <b>이것</b>을 현재로 만든다 (R8.8) — 검수를 기다리는 동안 작성자가 새 버전을
	 * 얹었다면, 사람이 본 것도 열리는 것도 그 최신본이어야 한다.
	 */
	@Transactional(value = "catalogTransactionManager", readOnly = true)
	public Optional<UUID> latestVersionId(UUID storyId) {
		return this.jdbc
				.sql("SELECT id FROM story_version WHERE story_id = ? ORDER BY version_no DESC LIMIT 1")
				.param(storyId).query(UUID.class).optional();
	}

	/**
	 * 검수를 기다리는 작품 한 건.
	 *
	 * <p><b>본문도 세계관도 담지 않는다.</b> 큐는 <b>무엇을 볼 차례인가</b>를 답하는 자리이고,
	 * 원고 원문은 열람 감사가 걸린 다른 문으로 본다 (S-5).
	 */
	public record AwaitingReview(UUID storyId, String title, UUID authorRef, String visibility,
			String reviewStatus, Instant createdAt) {
	}

	/** 작품의 상태 한 벌. */
	/**
	 * 작성자까지 담은 현재 상태 (#245).
	 *
	 * @param authorRef 작성자의 {@code player_ref}. <b>{@code user.id} 가 아니다</b> (§5.3)
	 */
	public record OwnedStory(UUID authorRef, String reviewStatus, String visibility) {
	}

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

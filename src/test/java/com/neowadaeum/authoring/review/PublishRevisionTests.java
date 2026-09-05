package com.neowadaeum.authoring.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.authoring.draft.DraftService;
import com.neowadaeum.authoring.draft.StoryDraftRepository;
import com.neowadaeum.catalog.publish.StoryPublisher;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * B-56 — <b>고친 것이 진행 중인 이야기를 흔들지 않는다</b> (R8.8, R2.1).
 *
 * <p>재제출은 작품을 늘리지 않고 <b>같은 작품에 버전을 얹는다.</b> 그리고 기본 엔딩이 없는
 * 버전은 현재가 되지 않는다 (R2.11, #54).
 *
 * <p><b>S-11 — 픽스처는 전부 가상의 문자열이다.</b>
 */
class PublishRevisionTests extends ContainerTestBase {

	private static final String PAYLOAD = """
			{"title":"봄의 학교","shortDescription":"짧은 소개","worldIntro":"소개",
			 "settingDetail":"봄의 학교에서 시작한다.",
			 "chapters":[{"title":"1장","summarySeed":"시작"}],
			 "endings":[{"label":"좋은 끝","epilogueText":"잘 끝났다."}]}
			""";

	/** 같은 원고를 고쳐 다시 낸 모양 — 작품 수준 값이 전부 바뀐다 (#358). */
	private static final String REVISED_PAYLOAD = """
			{"title":"여름의 학교","shortDescription":"바뀐 소개","worldIntro":"바뀐 세계관",
			 "settingDetail":"여름의 학교에서 시작한다.","genres":["fantasy"],
			 "chapters":[{"title":"1장","summarySeed":"시작"}],
			 "endings":[{"label":"좋은 끝","epilogueText":"잘 끝났다."}]}
			""";

	/** 커버의 객체 키. <b>URL 이 아니다</b> (#315, §13-72) — S-11: 가상의 문자열이다. */
	private static final String FIRST_COVER_KEY =
			"drafts/00000000-0000-4000-8000-0000000000d0/cover/00000000-0000-4000-8000-0000000000d1.jpg";

	private static final String REVISED_COVER_KEY =
			"drafts/00000000-0000-4000-8000-0000000000d0/cover/00000000-0000-4000-8000-0000000000d2.jpg";

	/** 커버까지 갈아 끼운 개정판 (#368). 승인 전이므로 작품 행은 아직 첫 발행의 커버를 든다. */
	private static final String REVISED_PAYLOAD_WITH_COVER = REVISED_PAYLOAD.replace("\"chapters\"",
			"\"coverImage\":\"" + REVISED_COVER_KEY + "\",\"chapters\"");

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
	private StoryPublisher publisher;

	/** #368 — 검수자가 무엇을 보고 판정하는가. 그 값이 어느 행에서 오는지가 여기서 갈린다. */
	@Autowired
	private ReviewManuscriptService manuscripts;

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
			jdbc.sql("""
							DELETE FROM story_version_genre WHERE story_version_id IN
									(SELECT id FROM story_version WHERE story_id = ?)
							""").param(storyId).update();
			jdbc.sql("DELETE FROM story_genre WHERE story_id = ?").param(storyId).update();
			jdbc.sql("DELETE FROM story_version WHERE story_id = ?").param(storyId).update();
			jdbc.sql("DELETE FROM story WHERE id = ?").param(storyId).update();
		}
		this.stories.clear();
		this.draftRows.deleteAll();
	}

	/**
	 * <b>재제출은 작품을 늘리지 않는다</b> (R8.8).
	 *
	 * <p>새 작품을 만들면 같은 이야기가 라이브러리에 둘이 되고, 도달률은
	 * {@code (story_id, ending_no)} 로 집계되므로 <b>통계가 갈라진다.</b>
	 */
	@Test
	void R8_8_a_resubmission_adds_a_version_to_the_same_story() {
		UUID draftId = givenDraft();
		UUID authorRef = authorOf(draftId);
		UUID storyId = submitted(authorRef, draftId, Visibility.UNLISTED);

		UUID again = submitted(authorRef, draftId, Visibility.UNLISTED);

		assertThat(again).isEqualTo(storyId);
		assertThat(versionNumbersOf(storyId)).containsExactly(1, 2);
	}

	/** 자동 승인된 개정판은 <b>곧바로 현재가 된다</b> (R8.8). */
	@Test
	void R8_8_an_auto_approved_revision_becomes_current() {
		UUID draftId = givenDraft();
		UUID authorRef = authorOf(draftId);
		UUID storyId = submitted(authorRef, draftId, Visibility.UNLISTED);
		UUID first = currentVersionOf(storyId);

		submitted(authorRef, draftId, Visibility.UNLISTED);

		assertThat(currentVersionOf(storyId)).isNotNull().isNotEqualTo(first);
	}

	/**
	 * <b>사람을 기다리는 개정판은 현재가 되지 않는다</b> (R8.6, I-8).
	 *
	 * <p>검수를 지나지 않은 버전이 현재가 되면 그것은 <b>승인 없이 노출된 UGC</b> 다.
	 */
	@Test
	void I8_a_revision_awaiting_human_review_does_not_become_current() {
		UUID draftId = givenDraft();
		UUID authorRef = authorOf(draftId);
		UUID storyId = submitted(authorRef, draftId, Visibility.UNLISTED);
		UUID approvedVersion = currentVersionOf(storyId);

		submitted(authorRef, draftId, Visibility.PUBLIC);

		assertThat(versionNumbersOf(storyId)).containsExactly(1, 2);
		assertThat(currentVersionOf(storyId)).isEqualTo(approvedVersion);
	}

	/** 사람이 통과시키면 <b>그 개정판이 현재가 된다</b> (R8.8). */
	@Test
	void R8_8_approving_a_revision_makes_the_newest_version_current() {
		UUID draftId = givenDraft();
		UUID authorRef = authorOf(draftId);
		UUID storyId = submitted(authorRef, draftId, Visibility.UNLISTED);
		UUID approvedVersion = currentVersionOf(storyId);
		submitted(authorRef, draftId, Visibility.PUBLIC);

		this.queue.decide(UUID.randomUUID(), storyId, ReviewVerdict.PASS, List.of(), null);

		assertThat(currentVersionOf(storyId)).isNotNull().isNotEqualTo(approvedVersion);
		assertThat(this.publisher.latestVersionId(storyId)).contains(currentVersionOf(storyId));
	}

	/**
	 * <b>§10.1-12 — 진행 중 세션은 새 버전에 영향받지 않는다</b> (R2.1, I-4).
	 *
	 * <p>세션이 고정한 버전이 그대로 남아 있어야 한다. 여기서 버전이 따라 움직이면 작성자가
	 * 엔딩 조건을 고치는 순간 <b>플레이 중인 모든 사람의 이야기가 바뀐다.</b>
	 */
	@Test
	void S10_1_12_a_pinned_version_survives_a_new_version() {
		UUID draftId = givenDraft();
		UUID authorRef = authorOf(draftId);
		UUID storyId = submitted(authorRef, draftId, Visibility.UNLISTED);
		UUID pinned = currentVersionOf(storyId);

		submitted(authorRef, draftId, Visibility.UNLISTED);

		// 고정된 버전의 챕터·엔딩이 그대로 있다 — 세션은 이것을 계속 읽는다.
		assertThat(rowCount("chapter_def", pinned)).isEqualTo(1);
		assertThat(rowCount("ending_def", pinned)).isEqualTo(2);
		assertThat(currentVersionOf(storyId)).isNotEqualTo(pinned);
	}

	/**
	 * <b>폴백 없는 버전은 현재가 되지 않는다</b> (R2.2, R2.11, #54).
	 *
	 * <p>기본 엔딩이 없으면 어떤 조건에도 걸리지 않은 세션이 끝나지 못한다. DB 로는 막을 수
	 * 없는 절반이다 — <b>행의 부재</b>는 CHECK 가 보지 못한다.
	 */
	@Test
	void R2_11_a_version_without_a_default_ending_does_not_become_current() {
		UUID draftId = givenDraft();
		UUID storyId = submitted(authorOf(draftId), draftId, Visibility.UNLISTED);
		UUID approvedVersion = currentVersionOf(storyId);
		UUID orphan = givenVersionWithoutDefaultEnding(storyId);

		assertThatThrownBy(() -> this.publisher.markCurrent(storyId, orphan))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR);

		assertThat(currentVersionOf(storyId)).isEqualTo(approvedVersion);
	}

	/**
	 * <b>엔딩이 없는 원고는 정상이다</b> (#54).
	 *
	 * <p>이걸 함께 확인하지 않으면 하한 검증이 <b>작성 흐름을 막는 수정</b>이 된다 — 5단계
	 * 작성에서 엔딩은 마지막에 온다.
	 */
	@Test
	void R2_11_a_draft_without_endings_is_a_normal_state() {
		UUID authorRef = UUID.randomUUID();
		UUID draftId = this.drafts.create(authorRef).getId();

		this.drafts.save(authorRef, draftId, 3,
				"{\"title\":\"봄의 학교\",\"settingDetail\":\"봄의 학교에서 시작한다.\"}");

		assertThat(this.draftRows.findById(draftId)).isPresent();
	}

	/**
	 * <b>승인이 작품 수준 값을 함께 옮긴다</b> (#358, §13-74).
	 *
	 * <p>지금까지 {@code publishRevision} 은 새 버전만 얹고 {@code story} 행을 건드리지 않았다 —
	 * 작성자가 제목을 바꿔 재제출해도 <b>카탈로그는 첫 발행의 값을 계속 보여 줬다.</b>
	 */
	@Test
	void S13_74_approving_a_revision_carries_the_story_level_fields() {
		UUID draftId = givenDraft();
		UUID authorRef = authorOf(draftId);
		UUID storyId = submitted(authorRef, draftId, Visibility.UNLISTED);
		assertThat(storyFieldsOf(storyId)).containsExactly("봄의 학교", "짧은 소개", "소개");

		this.drafts.save(authorRef, draftId, 5, REVISED_PAYLOAD);
		submitted(authorRef, draftId, Visibility.UNLISTED);

		assertThat(storyFieldsOf(storyId)).containsExactly("여름의 학교", "바뀐 소개", "바뀐 세계관");
	}

	/**
	 * <b>검수 중인 값이 라이브러리에 먼저 뜨지 않는다</b> (R8.8, I-8).
	 *
	 * <p>이것이 갱신을 {@code publishRevision} 이 아니라 {@code markCurrent} 에 둔 이유다 —
	 * 버전을 나눈 목적이 그것이고, 여기서 옮기면 <b>승인 없이 노출된 UGC</b> 가 된다.
	 */
	@Test
	void I8_a_revision_awaiting_human_review_does_not_change_the_story_row() {
		UUID draftId = givenDraft();
		UUID authorRef = authorOf(draftId);
		UUID storyId = submitted(authorRef, draftId, Visibility.UNLISTED);

		this.drafts.save(authorRef, draftId, 5, REVISED_PAYLOAD);
		submitted(authorRef, draftId, Visibility.PUBLIC);

		assertThat(storyFieldsOf(storyId)).containsExactly("봄의 학교", "짧은 소개", "소개");
	}

	/** 사람이 통과시킨 그 순간에 옮겨진다 (#358). */
	@Test
	void S13_74_human_approval_promotes_the_story_level_fields() {
		UUID draftId = givenDraft();
		UUID authorRef = authorOf(draftId);
		UUID storyId = submitted(authorRef, draftId, Visibility.UNLISTED);
		this.drafts.save(authorRef, draftId, 5, REVISED_PAYLOAD);
		submitted(authorRef, draftId, Visibility.PUBLIC);

		this.queue.decide(UUID.randomUUID(), storyId, ReviewVerdict.PASS, List.of(), null);

		assertThat(storyFieldsOf(storyId)).containsExactly("여름의 학교", "바뀐 소개", "바뀐 세계관");
	}

	/**
	 * <b>장르는 더해지지 않고 맞춰진다</b> (#358).
	 *
	 * <p>더하기만 하면 한 번 고른 장르가 영영 남고, 그 작품은 <b>작성자가 지운 섹션에 계속
	 * 뜬다</b> — 작성자는 남의 눈으로 라이브러리를 봐야 그것을 안다 (§13-72 와 같은 자리).
	 */
	@Test
	void S13_74_genres_are_replaced_not_accumulated() {
		UUID draftId = givenDraft();
		UUID authorRef = authorOf(draftId);
		this.drafts.save(authorRef, draftId, 5, PAYLOAD.replace("\"chapters\"",
				"\"genres\":[\"romance\"],\"chapters\""));
		UUID storyId = submitted(authorRef, draftId, Visibility.UNLISTED);
		assertThat(genreKeysOf(storyId)).containsExactly("romance");

		this.drafts.save(authorRef, draftId, 5, REVISED_PAYLOAD);
		submitted(authorRef, draftId, Visibility.UNLISTED);

		assertThat(genreKeysOf(storyId)).containsExactly("fantasy");
	}

	/**
	 * <b>검수자는 심사 중인 버전의 장르와 커버를 본다</b> (#368, §13-77).
	 *
	 * <p>제목과 같은 이유다 (§13-74). 승인이 작품 행의 장르를 <b>이 버전의 것으로 맞추므로</b>
	 * (#358), 작품 행을 읽으면 검수자는 <b>자기가 승인하는 것과 다른 섹션</b>을 보고 판정한다 —
	 * 커버는 15세 등급 판정의 대상이므로 (R8.5) 같은 어긋남이 더 비싸다.
	 *
	 * <p>작품 행이 아직 첫 발행의 값을 들고 있다는 것을 함께 단언한다 — 그것이 없으면 이
	 * 테스트는 <b>어느 행을 읽었는지 구분하지 못한다.</b>
	 */
	@Test
	void S13_77_the_manuscript_reads_the_version_awaiting_review() {
		UUID draftId = givenDraft();
		UUID authorRef = authorOf(draftId);
		this.drafts.save(authorRef, draftId, 5, PAYLOAD.replace("\"chapters\"",
				"\"genres\":[\"romance\"],\"coverImage\":\"" + FIRST_COVER_KEY + "\",\"chapters\""));
		UUID storyId = submitted(authorRef, draftId, Visibility.UNLISTED);

		this.drafts.save(authorRef, draftId, 5, REVISED_PAYLOAD_WITH_COVER);
		submitted(authorRef, draftId, Visibility.PUBLIC);

		ReviewManuscript manuscript = this.manuscripts.read(UUID.randomUUID(), storyId);

		assertThat(manuscript.genres()).extracting(ReviewManuscript.ManuscriptGenre::key)
				.containsExactly("fantasy");
		assertThat(manuscript.coverImageKey()).isEqualTo(REVISED_COVER_KEY);
		assertThat(genreKeysOf(storyId)).containsExactly("romance");
	}

	/**
	 * <b>제목은 지워지지 않는다.</b> {@code story.title} 은 NOT NULL 이고, 발행 경로 밖에서
	 * 만들어진 버전(공식 시드)은 작품 수준 스냅샷을 갖지 않는다 — 그 버전이 현재가 되어도
	 * 작품의 이름은 남는다.
	 */
	@Test
	void S13_74_a_version_without_a_snapshot_does_not_erase_the_title() {
		UUID draftId = givenDraft();
		UUID storyId = submitted(authorOf(draftId), draftId, Visibility.UNLISTED);
		UUID bare = givenVersionWithoutStoryFields(storyId);

		this.publisher.markCurrent(storyId, bare);

		assertThat(storyFieldsOf(storyId).getFirst()).isEqualTo("봄의 학교");
	}

	/**
	 * 작품 수준 스냅샷이 없는 버전 — 공식 작품 시드(V3)가 이 모양이다.
	 *
	 * <p>기본 엔딩을 갖추므로 {@code markCurrent} 를 통과한다. 확인하려는 것은 <b>그 통과가
	 * 이름을 지우지 않는가</b> 다.
	 */
	private UUID givenVersionWithoutStoryFields(UUID storyId) {
		JdbcClient jdbc = JdbcClient.create(this.catalog);
		UUID versionId = UUID.randomUUID();
		OffsetDateTime now = Instant.now().atOffset(ZoneOffset.UTC);
		jdbc.sql("""
						INSERT INTO story_version (id, story_id, version_no, world_prompt, choice_policy,
								state_schema, state_template_key, published_at)
						VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
						""")
				.params(versionId, storyId, 98, "봄의 학교에서 시작한다.",
						"{\"min\":1,\"max\":4,\"preferred\":3}", "{\"flags\":[]}", "affinity", now)
				.update();
		jdbc.sql("""
						INSERT INTO ending_def (id, story_version_id, story_id, ending_no, label,
								epilogue_text, condition, is_default, is_secret)
						VALUES (?, ?, ?, ?, ?, ?, NULL, true, false)
						""")
				.params(UUID.randomUUID(), versionId, storyId, 1, "이야기의 끝", "")
				.update();
		return versionId;
	}

	/** 작품 행이 지금 들고 있는 것 — 독자가 보는 값이다. */
	private List<String> storyFieldsOf(UUID storyId) {
		return JdbcClient.create(this.catalog)
				.sql("SELECT title, short_desc, world_intro FROM story WHERE id = ?").param(storyId)
				.query((rs, rowNum) -> List.of(rs.getString("title"), rs.getString("short_desc"),
						rs.getString("world_intro")))
				.single();
	}

	private List<String> genreKeysOf(UUID storyId) {
		return JdbcClient.create(this.catalog).sql("""
						SELECT g.key FROM story_genre sg JOIN genre g ON g.id = sg.genre_id
						WHERE sg.story_id = ? ORDER BY g.key
						""").param(storyId).query(String.class).list();
	}

	private UUID submitted(UUID authorRef, UUID draftId, Visibility visibility) {
		UUID storyId = this.submissions.submit(authorRef, draftId, visibility).storyId();
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

	/**
	 * 기본 엔딩이 없는 버전을 <b>직접</b> 만든다.
	 *
	 * <p>{@code StoryDefinition} 은 기본 엔딩을 하나 요구하므로 정상 경로로는 이 상태를 만들 수
	 * 없다 — 그래서 SQL 로 만든다. 막으려는 것은 <b>그 경로 밖에서 들어오는 버전</b>이다 (S-7).
	 */
	private UUID givenVersionWithoutDefaultEnding(UUID storyId) {
		JdbcClient jdbc = JdbcClient.create(this.catalog);
		UUID versionId = UUID.randomUUID();
		OffsetDateTime now = Instant.now().atOffset(ZoneOffset.UTC);
		jdbc.sql("""
						INSERT INTO story_version (id, story_id, version_no, world_prompt, choice_policy,
								state_schema, state_template_key, published_at)
						VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
						""")
				.params(versionId, storyId, 99, "봄의 학교에서 시작한다.",
						"{\"min\":1,\"max\":4,\"preferred\":3}", "{\"flags\":[]}", "affinity", now)
				.update();
		// 일반 엔딩은 조건을 반드시 갖는다 (V4 의 CHECK, §13-16).
		jdbc.sql("""
						INSERT INTO ending_def (id, story_version_id, story_id, ending_no, label,
								epilogue_text, condition, is_default, is_secret)
						VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, false, false)
						""")
				.params(UUID.randomUUID(), versionId, storyId, 1, "좋은 끝", "잘 끝났다.",
						"{\"turnGte\":1000000}")
				.update();
		return versionId;
	}

	private List<Integer> versionNumbersOf(UUID storyId) {
		return JdbcClient.create(this.catalog)
				.sql("SELECT version_no FROM story_version WHERE story_id = ? ORDER BY version_no")
				.param(storyId).query(Integer.class).list();
	}

	private UUID currentVersionOf(UUID storyId) {
		return JdbcClient.create(this.catalog)
				.sql("SELECT current_version_id FROM story WHERE id = ?").param(storyId)
				.query(UUID.class).optional().orElse(null);
	}

	private int rowCount(String table, UUID versionId) {
		return JdbcClient.create(this.catalog)
				.sql("SELECT COUNT(*) FROM " + table + " WHERE story_version_id = ?").param(versionId)
				.query(Integer.class).single();
	}
}

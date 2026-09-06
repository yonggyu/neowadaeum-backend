package com.neowadaeum.catalog.publish;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.authoring.draft.DraftService;
import com.neowadaeum.authoring.draft.StoryDraftRepository;
import com.neowadaeum.authoring.review.ReviewStatus;
import com.neowadaeum.authoring.review.SubmissionService;
import com.neowadaeum.authoring.review.Visibility;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * R8.11 — <b>사후 재스캔이 읽는 목록도 조용히 짧아지지 못하게 한다</b> (§13-84, #392).
 *
 * <p>같은 물음 — <i>이 작품의 어떤 문장을 검수하는가</i> — 에 답하는 목록이 <b>두 곳에 손으로
 * 적혀 있다.</b> L1 은 원고 계약의 이름을 읽고 ({@code SubmissionService.fieldsOf}) 재스캔은
 * 발행물의 컬럼을 읽는다 ({@link PublishedStoryTexts} 의 SQL). <b>한쪽만 늘어나는 일이 두
 * 번 있었다</b> — #350 이 인물을 발행하기 시작했을 때 L1 이 못 따라갔고(#366), 그것을 고쳤을
 * 때 재스캔이 못 따라갔다(#372). 두 번 다 빠진 칸은 예외를 내지 않았다.
 *
 * <p><b>두 목록을 합치지 않는다.</b> 읽는 것이 다르고 검수 시점도 다르다 — 제출 시점에는
 * 발행물이 아직 없고, 사후 시점에는 원고가 이미 바뀌었을 수 있다. 모양이 비슷하다는 이유로
 * 합치면 잘못된 추상화가 된다.
 *
 * <p><b>대신 L1 쪽 {@code SubmissionFieldCoverageTests} 가 세운 모양을 여기에도 세운다</b> —
 * 발행물의 텍스트 성분을 전부 세고, 걸지도 이유와 함께 제외하지도 않은 성분이 있으면
 * <b>실패한다.</b> 그때 사람이 <i>이것이 검수 대상인가</i> 를 정하고 둘 중 한 목록에 적는다.
 *
 * <p><b>대조표를 만들지 않는다.</b> 두 목록이 같은 개수·같은 의미를 덮는지 기계적으로 맞추려면
 * 이름이 다르므로 매핑 표가 필요하고, <b>그 표가 세 번째 손으로 적은 목록</b>이 된다.
 *
 * <p><b>S-11 — 픽스처는 전부 가상의 표식 문자열이다.</b> 블록리스트를 쓰지 않는다: 확인하는
 * 것은 판정이 아니라 <b>무엇이 판정기에 닿는가</b> 다.
 */
class PublishedStoryTextsCoverageTests extends ContainerTestBase {

	/** 표식은 컬럼마다 다르다 — 어느 성분이 새는지 실패 메시지가 스스로 말해야 한다. */
	private static final String MARK = "표식";

	/**
	 * 재스캔이 <b>거는</b> 컬럼.
	 *
	 * <p><b>{@code ending_def} 의 둘은 작성자가 적은 엔딩에만 걸린다</b> (§13-84, #397) —
	 * 서버가 더하는 기본 엔딩의 라벨은 작성자가 쓴 것이 아니다 (§13-16).
	 */
	private static final List<String> SCANNED = List.of("story.title", "story.short_desc",
			"story.world_intro", "story_version.world_prompt", "story_version.state_schema",
			"chapter_def.title", "chapter_def.summary_seed", "ending_def.label",
			"ending_def.epilogue_text", "character.name", "character.one_line",
			"character.persona_prompt");

	/**
	 * 작성자가 쓴 글이 아닌 컬럼과 <b>그 이유</b>.
	 *
	 * <p>이유를 함께 적는 이유는, 목록만 있으면 다음 사람이 <b>거슬리는 실패를 지우는 자리</b>로
	 * 쓰기 때문이다.
	 */
	private static final Map<String, String> NOT_AUTHOR_TEXT = Map.ofEntries(
			Map.entry("story.slug", "서버가 제목에서 만든 식별자다 (§13-15). 그 제목은 이미 걸린다"),
			Map.entry("story.cover_url", "업로드가 확정한 객체 키다 (#315). 이미지는 사람이 판정한다 (§13-83)"),
			Map.entry("story.hero_url", "같은 종류의 객체 키이고, UGC 발행 경로는 이 칸을 쓰지 않는다"),
			Map.entry("story.description", "공식 작품의 긴 소개다. UGC 발행 경로는 이 칸을 쓰지 않는다"),
			Map.entry("story.author_type", "'official' 과 'user' 뿐이다. 플랫폼 값이다"),
			Map.entry("story.visibility", "넓이의 눈금이다 (§2.3). 작성자가 고르는 것은 목록 중 하나다"),
			Map.entry("story.review_status", "검수가 정하는 상태다 (§13-9)"),
			Map.entry("story.pending_visibility", "통과가 열 자리다 (§13-83). 같은 눈금의 값이다"),
			Map.entry("story_version.choice_policy", "서버 상수다. 작성자가 정하지 않는다"),
			Map.entry("story_version.state_template_key", "플랫폼 템플릿 키다 (R4.4, §13-9)"),
			Map.entry("story_version.title", "#358 의 스냅샷이다. 정본은 작품 행이고 그쪽이 걸린다"),
			Map.entry("story_version.short_desc", "같은 스냅샷이다 (§13-74)"),
			Map.entry("story_version.world_intro", "같은 스냅샷이다 (§13-74)"),
			Map.entry("story_version.cover_url", "같은 스냅샷이고, 그 값은 객체 키다 (§13-83)"),
			Map.entry("chapter_def.entry_condition", "서버가 템플릿에서 조립한다 (R7.16, §13-69)"),
			Map.entry("ending_def.condition", "같은 이유다 — 작성자가 보낸 것은 고른 것뿐이다"),
			Map.entry("ending_def.visual_url", "객체 키다 (#315). 이미지는 사람이 판정한다 (§13-83)"),
			Map.entry("character.role", "발행 경로가 쓰지 않는다 — 원고 계약에 그 칸이 없다 (§13-70)"),
			Map.entry("character.portrait_url", "업로드가 확정한 객체 키다 (#315, §13-83)"),
			// 아래 셋은 위 세 컬럼의 개명분이다 (#396, §13-85). 개명이 끝날 때까지 옛 이름과
			// 함께 서므로 같은 이유가 두 자리에 적힌다 — 옛 컬럼이 빠지는 날 이 줄만 남는다.
			Map.entry("story.cover_image_key", "업로드가 확정한 객체 키다 (#315). 이미지는 사람이 판정한다 (§13-83)"),
			Map.entry("story_version.cover_image_key", "같은 스냅샷이고, 그 값은 객체 키다 (§13-83)"),
			Map.entry("character.portrait_image_key", "업로드가 확정한 객체 키다 (#315, §13-83)"));

	@Autowired
	private PublishedStoryTexts published;

	@Autowired
	private SubmissionService submissions;

	@Autowired
	private DraftService drafts;

	@Autowired
	private StoryDraftRepository draftRows;

	@Autowired
	@Qualifier("catalogDataSource")
	private DataSource catalog;

	private final List<UUID> stories = new ArrayList<>();

	@AfterEach
	void clear() {
		JdbcClient jdbc = JdbcClient.create(this.catalog);
		for (UUID storyId : this.stories) {
			// 인물은 버전을 FK 로 참조한다 (§13-1). 남겨 두면 버전 삭제가 막히고, 그 예외가
			// 뒷정리의 나머지를 건너뛰어 **실패가 다음 테스트로 옮겨 붙는다.**
			jdbc.sql("DELETE FROM character WHERE story_id = ?").param(storyId).update();
			jdbc.sql("DELETE FROM chapter_def WHERE story_id = ?").param(storyId).update();
			jdbc.sql("DELETE FROM ending_def WHERE story_id = ?").param(storyId).update();
			jdbc.sql("DELETE FROM story_review WHERE story_id = ?").param(storyId).update();
			jdbc.sql("UPDATE story SET current_version_id = NULL WHERE id = ?").param(storyId).update();
			jdbc.sql("DELETE FROM story_version_genre WHERE story_version_id IN "
					+ "(SELECT id FROM story_version WHERE story_id = ?)").param(storyId).update();
			jdbc.sql("DELETE FROM story_version WHERE story_id = ?").param(storyId).update();
			jdbc.sql("DELETE FROM story WHERE id = ?").param(storyId).update();
		}
		this.stories.clear();
		this.draftRows.deleteAll();
	}

	/**
	 * <b>발행물에 텍스트 컬럼이 늘면 여기서 정해야 한다</b> (R8.11, §13-84).
	 *
	 * <p>이 실패는 버그가 아니라 <b>결정이 남았다</b>는 뜻이다 — 인물 표가 발행되기 시작한
	 * 날(#350) 이 테스트가 있었다면 그날 실패했고, 그러면 #372 는 열리지 않았다.
	 *
	 * <p><b>표를 손으로 적지 않는다.</b> 발행물은 <b>작품 행과 그 버전에 매달린 모든 것</b>이며,
	 * 그 목록은 스키마가 답한다 — 다음에 버전에 매달리는 표가 생기면 그 표의 컬럼이 여기
	 * 나타난다. 표 이름을 적어 두면 <b>새 표를 아무도 세지 않는다.</b>
	 */
	@Test
	void R8_11_a_new_text_column_of_the_published_story_must_be_decided_here() {
		assertThat(textColumnsOfThePublishedStory())
				.as("발행물의 텍스트 컬럼은 재스캔이 걸거나(SCANNED) 이유와 함께 제외하거나"
						+ "(NOT_AUTHOR_TEXT) 둘 중 하나다 (R8.11, §13-84)")
				.containsExactlyInAnyOrderElementsOf(union(SCANNED, NOT_AUTHOR_TEXT.keySet()));
	}

	/**
	 * <b>목록에 적힌 것이 실제로 판정기까지 간다.</b>
	 *
	 * <p>적어 두고 걸지 않으면 목록이 거짓이 되고, 그 거짓은 위의 세는 테스트를 통과한다 —
	 * 세는 쪽은 <b>결정했는가</b> 만 묻기 때문이다.
	 */
	@Test
	void R8_11_every_scanned_column_reaches_the_screen() {
		UUID storyId = givenApprovedStoryOfMarks();

		List<String> texts = scannedTextsOf(storyId);

		assertThat(SCANNED).allSatisfy(column -> assertThat(texts)
				.as("%s 가 재스캔에 닿지 않는다", column).contains(mark(column)));
	}

	/**
	 * <b>서버가 더하는 기본 엔딩은 걸지 않는다</b> (§13-84, #397).
	 *
	 * <p>그 라벨은 §13-16 이 더하는 <b>서버 상수</b>이고 작성자가 쓴 것이 아니다. 걸리면
	 * <b>모든 UGC 작품이 같은 문자열로 함께 내려간다</b> — 블록리스트가 그 낱말을 담는 날
	 * 재스캔 한 회차가 카탈로그를 비운다.
	 */
	@Test
	void S13_84_the_default_ending_the_server_adds_is_not_rescanned() {
		UUID storyId = givenApprovedStoryOfMarks();

		assertThat(scannedTextsOf(storyId))
				.as("작성자가 쓰지 않은 문자열은 판정 대상이 아니다")
				.doesNotContain(defaultEndingLabelOf(storyId));
	}

	// ── 픽스처 ──────────────────────────────────────────────

	private static String mark(String column) {
		return MARK + "-" + column;
	}

	/**
	 * 컬럼마다 다른 표식을 넣은 작품 하나.
	 *
	 * <p><b>제출 경로로 만든다.</b> 행을 직접 넣으면 발행이 그 컬럼을 채우는지까지는 확인되지
	 * 않는다 — 재스캔이 읽는 것은 <b>발행이 적은 값</b>이다.
	 */
	private UUID givenApprovedStoryOfMarks() {
		String payload = """
				{"title":"%s","shortDescription":"%s","worldIntro":"%s","settingDetail":"%s",
				 "flags":["%s"],
				 "characters":[{"name":"%s","oneLine":"%s","persona":"%s"}],
				 "chapters":[{"title":"%s","summarySeed":"%s"}],
				 "endings":[{"label":"%s","epilogueText":"%s"}]}
				""".formatted(mark("story.title"), mark("story.short_desc"),
				mark("story.world_intro"), mark("story_version.world_prompt"),
				mark("story_version.state_schema"), mark("character.name"),
				mark("character.one_line"), mark("character.persona_prompt"),
				mark("chapter_def.title"), mark("chapter_def.summary_seed"),
				mark("ending_def.label"), mark("ending_def.epilogue_text"));

		UUID authorRef = UUID.randomUUID();
		UUID draftId = this.drafts.create(authorRef).getId();
		this.drafts.save(authorRef, draftId, 5, payload);
		var outcome = this.submissions.submit(authorRef, draftId, Visibility.UNLISTED);
		assertThat(outcome.reviewStatus())
				.as("픽스처가 자동 검수에서 걸렸다 — 표식은 가상의 문자열이어야 한다 (S-11)")
				.isEqualTo(ReviewStatus.APPROVED);
		this.stories.add(outcome.storyId());
		return outcome.storyId();
	}

	/** 재스캔이 이 작품에 대해 읽어 오는 문자열. */
	private List<String> scannedTextsOf(UUID storyId) {
		return this.published.approvedPage(100, null).stream()
				.filter(story -> story.storyId().equals(storyId)).findFirst()
				.map(PublishedStoryTexts.ApprovedStory::texts)
				.orElseThrow(() -> new AssertionError("승인된 UGC 한 쪽에 이 작품이 없다"));
	}

	private String defaultEndingLabelOf(UUID storyId) {
		return JdbcClient.create(this.catalog)
				.sql("SELECT label FROM ending_def WHERE story_id = ? AND is_default")
				.param(storyId).query(String.class).single();
	}

	// ── 성분 세기 ───────────────────────────────────────────

	/**
	 * 발행물의 텍스트 컬럼을 {@code 표.컬럼} 으로 모은다.
	 *
	 * <p><b>발행물은 작품 행과 그 버전에 매달린 모든 것이다.</b> 버전에 매달린 표는
	 * {@code story_version_id} 를 갖는 표이며, 그 사실을 스키마에 묻는다 — 표 이름을 여기
	 * 적어 두면 <b>다음에 생기는 표를 아무도 세지 않는다.</b>
	 *
	 * <p>세는 것은 문자열과 {@code jsonb} 다. 후자를 세는 이유는 {@code state_schema} 가
	 * 작성자가 선언한 이름을 담기 때문이다 (§13-80) — 형식이 JSON 이라는 것은 그 안에 작성자의
	 * 글이 없다는 뜻이 아니다.
	 */
	private List<String> textColumnsOfThePublishedStory() {
		JdbcClient jdbc = JdbcClient.create(this.catalog);
		Set<String> tables = new LinkedHashSet<>(List.of("story", "story_version"));
		tables.addAll(jdbc.sql("""
						SELECT DISTINCT table_name FROM information_schema.columns
						WHERE table_schema = current_schema() AND column_name = 'story_version_id'
						""")
				.query(String.class).list());

		return jdbc.sql("""
						SELECT table_name || '.' || column_name FROM information_schema.columns
						WHERE table_schema = current_schema()
						  AND data_type IN ('text', 'character varying', 'jsonb')
						  AND table_name IN (:tables)
						""")
				.param("tables", List.copyOf(tables)).query(String.class).list();
	}

	private static List<String> union(List<String> left, Set<String> right) {
		List<String> all = new ArrayList<>(left);
		all.addAll(right);
		return all;
	}
}

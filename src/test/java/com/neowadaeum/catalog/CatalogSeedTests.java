package com.neowadaeum.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.neowadaeum.ContainerTestBase;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-4 (#48) — 스키마가 <b>요구사항 원문과 일치하는지</b>, 시드가 들어갔는지, 규칙이 <b>DB 제약으로</b>
 * 성립하는지 확인한다.
 *
 * <p>기준 문서는 {@code docs/internal/backend-requirements.md} §2.3 Catalog 다. 비공개 원문이며
 * {@code .gitignore} 대상이라 레포에는 없다 — 그래서 <b>컬럼 목록을 여기에 박아 둔다.</b> 원문을 못 보는
 * 사람도 이 테스트만 보면 스키마가 무엇에 맞춰져 있는지 알 수 있고, 원문이 바뀌면 여기가 먼저 깨진다.
 *
 * <p>{@code story_version_id} 는 §2.3 이 아니라 {@code docs/corrections.md} §13-1 이 근거다.
 * 정정본이 상위 문서를 이긴다 (CLAUDE.md 의 우선순위).
 *
 * <p>엔티티를 거치지 않고 SQL 로 직접 찌른다. 엔티티를 거치면 <b>엔티티가 막았는지 DB 가 막았는지</b>
 * 구분되지 않는다. catalog 모듈에는 아직 엔티티가 없기도 하다 — B-08 은 슬라이스에서 제외돼 있다.
 *
 * <p>문구 대신 SQLState 로 본다. 메시지는 PostgreSQL 버전과 로케일에 따라 바뀐다.
 */
class CatalogSeedTests extends ContainerTestBase {

	/** PostgreSQL {@code unique_violation}. */
	private static final String UNIQUE_VIOLATION = "23505";

	/** PostgreSQL {@code check_violation}. */
	private static final String CHECK_VIOLATION = "23514";

	private static final UUID SEED_STORY = UUID.fromString("11111111-1111-4111-8111-000000000001");
	private static final UUID SEED_VERSION = UUID.fromString("11111111-1111-4111-8111-111111111111");

	@Autowired
	@Qualifier("catalogDataSource")
	private DataSource dataSource;

	// ── §2.3 원문 대조 ───────────────────────────────────────

	/** §2.3 {@code story} — 컬럼 이름과 구성이 원문 그대로여야 한다. I-19 상 age_rating 은 원문에도 없다. */
	@Test
	void S2_3_story_columns_match_the_requirement_source() throws SQLException {
		assertThat(columns("story")).containsExactlyInAnyOrder(
				"id", "slug", "title", "cover_url", "hero_url", "short_desc", "description",
				"world_intro", "author_type", "author_ref", "visibility", "review_status",
				"current_version_id", "published_at", "created_at");
	}

	/** §2.3 {@code story_version} — {@code choice_policy} 와 {@code state_schema} 가 빠지면 R4.1·R4.2 를 걸 곳이 없다. */
	@Test
	void S2_3_story_version_columns_match_the_requirement_source() throws SQLException {
		assertThat(columns("story_version")).containsExactlyInAnyOrder(
				"id", "story_id", "version_no", "world_prompt", "choice_policy",
				"state_schema", "state_template_key", "published_at");
	}

	/** §2.3 {@code character} + §13-1 — 원문 컬럼에 {@code story_version_id} 가 더해진다. */
	@Test
	void S2_3_character_columns_match_the_requirement_source() throws SQLException {
		assertThat(columns("character")).containsExactlyInAnyOrder(
				"id", "story_version_id", "story_id", "name", "role", "portrait_url", "one_line",
				"persona_prompt", "display_order", "is_visible_in_detail");
	}

	/** §2.3 {@code chapter_def} + §13-1. */
	@Test
	void S2_3_chapter_def_columns_match_the_requirement_source() throws SQLException {
		assertThat(columns("chapter_def")).containsExactlyInAnyOrder(
				"id", "story_version_id", "story_id", "chapter_no", "title", "entry_condition",
				"summary_seed", "min_turns", "max_turns");
	}

	/** §2.3 {@code ending_def} + §13-1. 원문 이름은 {@code epilogue_text} 다. */
	@Test
	void S2_3_ending_def_columns_match_the_requirement_source() throws SQLException {
		assertThat(columns("ending_def")).containsExactlyInAnyOrder(
				"id", "story_version_id", "story_id", "ending_no", "label", "epilogue_text",
				"condition", "visual_url", "is_secret", "is_default");
	}

	/**
	 * §13-1 — 챕터·엔딩·캐릭터는 <b>작품 버전</b>에 묶인다. 정정본이 §2.3 을 이긴다.
	 *
	 * <p>원문대로 {@code story_id} 로 묶으면 R2.1("세션은 story_version_id 를 고정 참조")과 R8.8 이
	 * 성립하지 않는다 — 작성자가 엔딩 조건을 고치는 순간 진행 중인 모든 세션이 영향을 받는다.
	 *
	 * <p>컬럼 유무가 아니라 <b>FK 가 실제로 story_version 을 가리키는지</b>를 본다.
	 */
	@Test
	void S13_1_version_scoped_tables_reference_story_version_not_story() throws SQLException {
		for (String table : List.of("chapter_def", "ending_def", "character")) {
			assertThat(foreignKeyTargets(table))
					.as("%s 의 FK 대상 (§13-1)", table)
					.containsExactly("story_version");
		}
	}

	// ── 시드 ─────────────────────────────────────────────────

	/** B-45 — 작품 1 / 챕터 6 / 엔딩 5 / 캐릭터 3. 축소판(S-4)에서 정식 분량으로 늘렸다. */
	@Test
	void B45_seed_provides_one_story_six_chapters_five_endings_three_characters() throws SQLException {
		assertThat(count("story")).isEqualTo(1);
		assertThat(count("story_version")).isEqualTo(1);
		assertThat(count("chapter_def")).isEqualTo(6);
		assertThat(count("ending_def")).isEqualTo(5);
		assertThat(count("character")).isEqualTo(3);
	}

	/**
	 * <b>B-44 가 요구하는 분량이 나오는가</b> (R7.2).
	 *
	 * <p>E2E 는 "시작→40턴→엔딩"을 재현한다. 챕터별 {@code max_turns} 합계가 40 에 못 미치면
	 * <b>재현할 대상 자체가 없다</b> — 축소판이 그랬다 (최대 9턴).
	 */
	@Test
	void B45_the_seed_can_carry_a_forty_turn_play() throws SQLException {
		assertThat(((Number) scalarObject("SELECT SUM(max_turns) FROM chapter_def")).intValue())
				.isGreaterThanOrEqualTo(40);
		assertThat(((Number) scalarObject("SELECT SUM(min_turns) FROM chapter_def")).intValue())
				.as("최소 진행으로도 끝나면 40턴 플레이가 조건에 걸려 조기 종료된다")
				.isLessThanOrEqualTo(40);
	}

	/**
	 * <b>R7.7 — 기본 엔딩은 정확히 하나이고 시크릿이 아니다.</b>
	 *
	 * <p>0개면 마지막 챕터에서 세션이 끝나지 못하고, 감춰진 기본 엔딩은 {@code totalEndings} 표기를
	 * 어긋나게 한다 (R7.11).
	 */
	@Test
	void R7_7_exactly_one_visible_default_ending() throws SQLException {
		assertThat(((Number) scalarObject("SELECT COUNT(*) FROM ending_def WHERE is_default")).intValue())
				.isEqualTo(1);
		assertThat(((Number) scalarObject(
				"SELECT COUNT(*) FROM ending_def WHERE is_default AND is_secret")).intValue()).isZero();
	}

	/**
	 * <b>넓은 조건이 좁은 조건을 가리지 않는다</b> (R7.6).
	 *
	 * <p>엔딩은 {@code ending_no} 순 최초 매칭이다. 시크릿 엔딩은 셋 모두를 요구하는 가장 좁은
	 * 조건이므로 <b>앞 번호</b>여야 한다 — 뒤에 두면 하나만 높아도 잡히는 엔딩이 먼저 매칭되어
	 * 아무도 그 엔딩을 보지 못한다. 그 사고는 에러가 아니라 침묵으로 나타난다.
	 */
	@Test
	void R7_6_the_narrowest_ending_is_evaluated_first() throws SQLException {
		assertThat(((Number) scalarObject(
				"SELECT MIN(ending_no) FROM ending_def WHERE condition IS NOT NULL")).intValue())
				.isEqualTo(((Number) scalarObject(
						"SELECT ending_no FROM ending_def WHERE is_secret")).intValue());
	}

	/**
	 * <b>조건이 참조하는 키가 전부 {@code state_schema} 에 선언돼 있다</b> (R4.1).
	 *
	 * <p>미정의 키 참조는 조건 평가기에서 {@code false} + 경고다 (S-6). 그러면 그 챕터·엔딩은
	 * <b>조용히 도달 불가능</b>해진다 — 시드에서 그것은 "플레이는 되는데 그 결말만 안 나온다"로
	 * 나타난다.
	 */
	@Test
	void R4_1_every_condition_key_is_declared_in_the_state_schema() throws SQLException {
		JsonNode schema = json("SELECT state_schema FROM story_version");
		java.util.Set<String> declared = new java.util.HashSet<>();
		schema.get("affinity").propertyNames().forEach(name -> declared.add("affinity." + name));
		schema.get("flags").forEach(flag -> declared.add("flags:" + flag.asString()));

		java.util.List<String> conditions = new java.util.ArrayList<>();
		conditions.addAll(queryStrings("SELECT entry_condition::TEXT FROM chapter_def WHERE entry_condition IS NOT NULL"));
		conditions.addAll(queryStrings("SELECT condition::TEXT FROM ending_def WHERE condition IS NOT NULL"));

		assertThat(conditions).isNotEmpty();
		for (String condition : conditions) {
			java.util.regex.Matcher numeric = java.util.regex.Pattern
					.compile("\"(affinity\\.[a-z_]+)\"").matcher(condition);
			while (numeric.find()) {
				assertThat(declared).as("%s 가 state_schema 에 없다", numeric.group(1)).contains(numeric.group(1));
			}
			java.util.regex.Matcher flags = java.util.regex.Pattern
					.compile("\"has\"\\s*:\\s*\\[\\s*\"flags\"\\s*,\\s*\"([a-z_]+)\"").matcher(condition);
			while (flags.find()) {
				assertThat(declared).as("flags.%s 가 state_schema 에 없다", flags.group(1))
						.contains("flags:" + flags.group(1));
			}
		}
	}

	/**
	 * R4.2 — 수치 필드는 {@code min} / {@code max} / {@code maxDeltaPerTurn} 을 갖는다.
	 *
	 * <p>셋 중 하나라도 없으면 S-5 의 clamp 가 걸 근거를 잃고, 그때 서버는 AI 가 제안한 변화량을
	 * 그대로 받게 된다 — I-15 가 무너지는 경로다.
	 */
	@Test
	void R4_2_numeric_state_fields_declare_min_max_and_delta_cap() throws SQLException {
		JsonNode affinity = json("SELECT state_schema FROM story_version").get("affinity").get("yuna");

		assertThat(affinity.get("min").asInt()).isZero();
		assertThat(affinity.get("max").asInt()).isEqualTo(100);
		assertThat(affinity.get("maxDeltaPerTurn").asInt()).isEqualTo(5);
	}

	/** §2.3 — choice_policy(min:1, max:4, preferred:3). 선택지 개수 계약의 출처다. */
	@Test
	void S2_3_choice_policy_carries_the_documented_bounds() throws SQLException {
		JsonNode policy = json("SELECT choice_policy FROM story_version");

		assertThat(policy.get("min").asInt()).isEqualTo(1);
		assertThat(policy.get("max").asInt()).isEqualTo(4);
		assertThat(policy.get("preferred").asInt()).isEqualTo(3);
	}

	/**
	 * §13-9 · R4.4 — {@code state_schema} 는 플랫폼 템플릿 중 하나여야 한다.
	 *
	 * <p>작성자가 자유 정의하게 두면 clamp 규칙(R4.2)과 disabled 판정을 일반화할 수 없다.
	 * 템플릿 키가 없으면 "어떤 규칙을 적용할 것인가"를 매번 스키마 모양에서 추론해야 한다.
	 */
	@Test
	void S13_9_state_schema_is_bound_to_a_platform_template() throws SQLException {
		assertThat((String) scalarObject("SELECT state_template_key FROM story_version"))
				.isEqualTo("affinity");
	}

	/** §13-9 — 템플릿은 affinity / flag / numeric 셋뿐이다. */
	@Test
	void S13_9_unknown_state_template_key_is_rejected() {
		assertRejected(CHECK_VIOLATION, () -> insertVersion("freeform"));
	}

	/** R2.3 — 공식 작품은 {@code author_type = 'official'} 이고 작성자 참조를 갖지 않는다. */
	@Test
	void R2_3_official_story_has_no_author_ref() throws SQLException {
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("SELECT author_type, author_ref FROM story")) {
			assertThat(rows.next()).isTrue();
			assertThat(rows.getString("author_type")).isEqualTo("official");
			assertThat(rows.getObject("author_ref")).isNull();
		}
	}

	/** R2.1 — {@code current_version_id} 는 실재하는 버전을 가리킨다. 순환 FK 대신 이 확인을 둔다. */
	@Test
	void R2_1_current_version_id_points_at_an_existing_version() throws SQLException {
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("""
						SELECT s.current_version_id, v.id
						FROM story s LEFT JOIN story_version v ON v.id = s.current_version_id
						""")) {
			assertThat(rows.next()).isTrue();
			assertThat(rows.getObject("current_version_id")).isNotNull();
			assertThat(rows.getObject(2)).as("current_version_id 가 실재하지 않는 버전을 가리킨다").isNotNull();
		}
	}

	/**
	 * R7.6 · R7.7 — 기본 엔딩은 {@code ending_no} 순회에서 마지막이어야 한다.
	 *
	 * <p>최초 매칭에서 종료하므로, 조건 없는 기본 엔딩이 앞에 오면 뒤의 조건부 엔딩은 <b>영원히
	 * 도달되지 않는다.</b> 행 간 관계라 CHECK 로 표현할 수 없어 시드에 대고 확인한다.
	 */
	@Test
	void R7_6_default_ending_is_last_in_ending_no_order() throws SQLException {
		List<Boolean> flags = new ArrayList<>();
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery(
						"SELECT is_default FROM ending_def ORDER BY ending_no")) {
			while (rows.next()) {
				flags.add(rows.getBoolean(1));
			}
		}
		assertThat(flags)
				.as("기본 엔딩은 마지막 번호다 — 앞에 있어도 평가에서 제외되지만(EndingEngine), "
						+ "읽는 사람에게 순서가 곧 우선순위로 보인다")
				.containsExactly(false, false, false, false, true);
	}

	/** R7.4 — 진입 조건은 GameState 참조식이다. 원문의 {@code all} / {@code gte} / {@code has} 조합을 쓴다. */
	@Test
	void R7_4_entry_condition_uses_the_documented_expression_grammar() throws SQLException {
		JsonNode condition = json("SELECT entry_condition FROM chapter_def WHERE chapter_no = 3");

		assertThat(condition.has("all")).isTrue();
		assertThat(condition.get("all").size()).isEqualTo(2);
		assertThat(condition.get("all").get(0).get("gte").get(0).asString()).isEqualTo("affinity.yuna");
		assertThat(condition.get("all").get(1).get("has").get(0).asString()).isEqualTo("flags");
	}

	/**
	 * S-3 와 S-4 가 같은 작품을 가리킨다.
	 *
	 * <p>시드의 {@code story_version.id} 와 {@code FixedStoryProvider} 데모 시나리오의
	 * {@code storyVersionRef} 가 어긋나면 S-9 에서 조용히 "시나리오에 없는 요청"이 된다.
	 */
	@Test
	void S4_seed_version_matches_the_fixed_story_scenario_reference() throws Exception {
		JsonNode scenario;
		try (InputStream in = new ClassPathResource("scenarios/demo-first-day.json").getInputStream()) {
			scenario = JsonMapper.builder().build().readTree(in);
		}

		assertThat(UUID.fromString(scenario.get("storyVersionRef").asString()))
				.as("데모 시나리오와 시드가 같은 작품 버전을 가리켜야 한다")
				.isEqualTo(SEED_VERSION);
		assertThat(scalar("SELECT id FROM story_version")).isEqualTo(SEED_VERSION);
	}

	// ── 제약 ─────────────────────────────────────────────────

	/** R2.2 — 기본 엔딩은 작품 버전당 정확히 1개다. 2개면 어느 쪽으로 끝나는지가 행 순서에 달린다. */
	@Test
	void R2_2_a_second_default_ending_is_rejected() {
		assertRejected(UNIQUE_VIOLATION, () -> insertEnding(99, null, true, false));
	}

	/** R7.11 — 폴백이 총계에서 감춰지면 미도달 엔딩의 존재가 드러나고 도달률 표기가 어긋난다. */
	@Test
	void R7_11_default_ending_cannot_be_secret() {
		assertRejected(CHECK_VIOLATION, () -> insertEnding(98, null, true, true));
	}

	/**
	 * §13-16 결정 2 — 일반 엔딩은 {@code condition} 을 반드시 갖는다.
	 *
	 * <p>조건 없는 일반 엔딩이 하나라도 들어가면 R7.6 의 {@code ending_no} 순회에서 <b>항상 최초
	 * 매칭</b>이 되어 뒤의 엔딩을 전부 가린다. 증상은 "엔딩이 하나밖에 안 나온다"로 보이고 원인이
	 * 데이터라는 것을 찾는 데 시간이 든다.
	 */
	@Test
	void S13_16_non_default_ending_requires_a_condition() {
		assertRejected(CHECK_VIOLATION, () -> insertEnding(97, null, false, false));
	}

	/**
	 * §13-16 결정 1 — 기본 엔딩은 {@code condition} 을 갖지 않는다.
	 *
	 * <p>기본 엔딩은 조건 판정에 참여하지 않는 fallback 이다 (R7.7). 조건을 가지면 폴백이 아니다.
	 */
	@Test
	void S13_16_default_ending_must_not_carry_a_condition() {
		assertRejected(CHECK_VIOLATION,
				() -> insertEnding(96, "{\"gte\": [\"affinity.yuna\", 1]}", true, false));
	}

	/** R7.2 — min_turns 충족 후 조건 평가, 불만족이면 max_turns 강제 전환. max < min 이면 성립하지 않는다. */
	@Test
	void R7_2_chapter_max_turns_must_not_be_below_min_turns() {
		assertRejected(CHECK_VIOLATION, () -> insertChapter(99, 5, 2));
	}

	/** 챕터 번호는 작품 버전 안에서 유일하다. 중복되면 전환 판정이 어느 행을 볼지 정해지지 않는다. */
	@Test
	void R7_2_chapter_number_is_unique_within_a_version() {
		assertRejected(UNIQUE_VIOLATION, () -> insertChapter(1, 1, 3));
	}

	/** §2.3 — {@code short_desc(≤40자)}. 카드 레이아웃이 이 길이를 전제한다. */
	@Test
	void S2_3_short_desc_longer_than_forty_characters_is_rejected() {
		assertRejected(CHECK_VIOLATION, () -> insertStory("too-long", "가".repeat(41), "official"));
	}

	/** §2.3 — {@code author_type} 은 official / user 둘뿐이다. */
	@Test
	void S2_3_unknown_author_type_is_rejected() {
		assertRejected(CHECK_VIOLATION, () -> insertStory("bad-author-type", "짧은 설명", "platform"));
	}

	/** [결정 필요] §13-15 — 유일하지 않은 slug 는 식별자로 기능하지 못한다. 기본 채택안은 UNIQUE 다. */
	@Test
	void S13_15_duplicate_slug_is_rejected() {
		assertRejected(UNIQUE_VIOLATION, () -> insertStory("first-day", "짧은 설명", "official"));
	}

	/**
	 * R13.1 (#269) — 공식 작품은 {@code author_ref} 를 가질 수 없다.
	 *
	 * <p>이 제약이 없으면 공식 작품 행에 {@code author_ref} 가 채워지는 순간 상세 경로만 그것을
	 * 읽어 닉네임을 표시한다 — 목록은 조인 조건으로 막지만 그 방어는 조회마다 반복해야 한다.
	 */
	@Test
	void R13_1_official_story_cannot_have_author_ref() {
		assertRejected(CHECK_VIOLATION,
				() -> insertStory("official-with-author-ref", "짧은 설명", "official", UUID.randomUUID()));
	}

	/**
	 * R13.1 (#269) — 사용자 작품은 {@code author_ref} 없이 존재할 수 없다.
	 *
	 * <p>양방향 CHECK 라 반대 방향도 함께 막힌다 — "작성자 없는 UGC" 는 표시할 이름이 없는 행이다.
	 */
	@Test
	void R13_1_user_story_requires_author_ref() {
		assertRejected(CHECK_VIOLATION,
				() -> insertStory("user-without-author-ref", "짧은 설명", "user", null));
	}

	// ── helpers ──────────────────────────────────────────────

	private void insertStory(String slug, String shortDesc, String authorType) throws SQLException {
		insertStory(slug, shortDesc, authorType, null);
	}

	private void insertStory(String slug, String shortDesc, String authorType, UUID authorRef)
			throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO story (id, slug, title, short_desc, author_type, author_ref,
						                   visibility, review_status, created_at)
						VALUES (?, ?, '검증용', ?, ?, ?, 'private', 'draft', now())
						""")) {
			statement.setObject(1, UUID.randomUUID());
			statement.setString(2, slug);
			statement.setString(3, shortDesc);
			statement.setString(4, authorType);
			statement.setObject(5, authorRef);
			statement.executeUpdate();
		}
	}

	private void insertVersion(String templateKey) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO story_version (id, story_id, version_no, world_prompt,
						                           choice_policy, state_schema, state_template_key, published_at)
						VALUES (?, ?, 99, '검증용', '{}'::JSONB, '{}'::JSONB, ?, now())
						""")) {
			statement.setObject(1, UUID.randomUUID());
			statement.setObject(2, SEED_STORY);
			statement.setString(3, templateKey);
			statement.executeUpdate();
		}
	}

	private void insertEnding(int endingNo, String condition, boolean isDefault, boolean isSecret)
			throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO ending_def (id, story_version_id, story_id, ending_no, label,
						                        epilogue_text, condition, is_default, is_secret)
						VALUES (?, ?, ?, ?, '검증용', '에필로그', CAST(? AS JSONB), ?, ?)
						""")) {
			statement.setObject(1, UUID.randomUUID());
			statement.setObject(2, SEED_VERSION);
			statement.setObject(3, SEED_STORY);
			statement.setInt(4, endingNo);
			statement.setString(5, condition);
			statement.setBoolean(6, isDefault);
			statement.setBoolean(7, isSecret);
			statement.executeUpdate();
		}
	}

	private void insertChapter(int chapterNo, int minTurns, int maxTurns) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO chapter_def (id, story_version_id, story_id, chapter_no, title,
						                         entry_condition, min_turns, max_turns)
						VALUES (?, ?, ?, ?, '검증용', NULL, ?, ?)
						""")) {
			statement.setObject(1, UUID.randomUUID());
			statement.setObject(2, SEED_VERSION);
			statement.setObject(3, SEED_STORY);
			statement.setInt(4, chapterNo);
			statement.setInt(5, minTurns);
			statement.setInt(6, maxTurns);
			statement.executeUpdate();
		}
	}

	/**
	 * {@code jsonb} 는 JDBC 로 {@code PGobject} 로 온다. {@code getObject} 를 String 으로 캐스팅하면
	 * {@code ClassCastException} 이 난다 — 텍스트 표현으로 받아 파싱한다.
	 */
	private JsonNode json(String sql) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery(sql)) {
			assertThat(rows.next()).as("행이 없다: %s", sql).isTrue();
			return JsonMapper.builder().build().readTree(rows.getString(1));
		}
	}

	private int count(String table) throws SQLException {
		return ((Number) scalarObject("SELECT count(*) FROM " + table)).intValue();
	}

	private UUID scalar(String sql) throws SQLException {
		return (UUID) scalarObject(sql);
	}

	private Object scalarObject(String sql) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery(sql)) {
			assertThat(rows.next()).as("행이 없다: %s", sql).isTrue();
			return rows.getObject(1);
		}
	}

	private List<String> columns(String table) throws SQLException {
		return queryStrings("""
				SELECT column_name FROM information_schema.columns
				WHERE table_schema = current_schema() AND table_name = '%s'
				""".formatted(table));
	}

	private List<String> foreignKeyTargets(String table) throws SQLException {
		return queryStrings("""
				SELECT DISTINCT rt.relname
				FROM pg_constraint c
				JOIN pg_class t  ON t.oid  = c.conrelid
				JOIN pg_class rt ON rt.oid = c.confrelid
				WHERE c.contype = 'f' AND t.relname = '%s'
				""".formatted(table));
	}

	private List<String> queryStrings(String sql) throws SQLException {
		List<String> values = new ArrayList<>();
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery(sql)) {
			while (rows.next()) {
				values.add(rows.getString(1));
			}
		}
		return values;
	}

	private void assertRejected(String expectedSqlState, ThrowingInsert insert) {
		try {
			insert.run();
			fail("DB 가 거절해야 하는 INSERT 가 통과했다 (기대 SQLState %s)", expectedSqlState);
		}
		catch (SQLException ex) {
			assertThat(ex.getSQLState()).as("실제 오류: %s", ex.getMessage()).isEqualTo(expectedSqlState);
		}
	}

	@FunctionalInterface
	private interface ThrowingInsert {

		void run() throws SQLException;
	}
}

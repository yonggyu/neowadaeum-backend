package com.neowadaeum.catalog.publish;

import com.neowadaeum.common.spi.PinnedStoryVersions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 게시된 UGC 의 <b>검수 대상 원문</b>을 읽는다 (R9.4, B-59).
 *
 * <p><b>현재 버전과 진행 중 세션이 붙들고 있는 버전을 본다</b> (§13-80, #372). 재스캔이 묻는
 * 것은 <b>지금 읽히고 있는 것이 오늘 기준으로도 괜찮은가</b>이고, 세션은 생성 시 버전에
 * 고정되므로 (I-4) 옛 버전도 <b>진행 중인 세션이 있는 동안 매 턴 모델에 실려 나간다.</b>
 * 현재 버전만 훑으면 읽히고 있는데 검사되지 않는 자리가 남는다. 아무 세션도 붙들지 않는 옛
 * 버전은 대상이 아니다 — 그것은 아무도 새로 읽지 않는다.
 *
 * <p><b>공식 작품은 대상이 아니다.</b> 재스캔은 UGC 사후 관리이며 (§8.4), 시드 작품까지 훑으면
 * 운영이 직접 넣은 것을 배치가 내리는 일이 생긴다.
 *
 * <p><b>필드 경로를 만들지 않는다.</b> {@code chapters[1].title} 같은 표기는 작성 화면이
 * 밑줄을 긋기 위한 것이고 ({@code authoring} 의 어휘다), 재스캔이 남기는 것은 <b>카테고리</b>
 * 뿐이다 (R8.7) — 여기서는 검사할 문자열만 모아 준다.
 */
@Service
public class PublishedStoryTexts {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String APPROVED_SELECT = """
			SELECT s.id, s.visibility, s.current_version_id, s.title, s.short_desc,
			       s.world_intro, v.world_prompt, v.state_schema::text AS state_schema
			FROM story s
			JOIN story_version v ON v.id = s.current_version_id
			WHERE s.review_status = 'approved' AND s.author_type = 'user'
			""";

	private static final String APPROVED_FIRST_PAGE = APPROVED_SELECT + " ORDER BY s.id LIMIT ?";

	private static final String APPROVED_NEXT_PAGE =
			APPROVED_SELECT + " AND s.id > ? ORDER BY s.id LIMIT ?";

	private static final String APPROVED_REF_SELECT = """
			SELECT s.id, s.visibility
			FROM story s
			WHERE s.review_status = 'approved' AND s.author_type = 'user'
			  AND s.current_version_id IS NOT NULL
			""";

	private static final String APPROVED_REF_FIRST_PAGE =
			APPROVED_REF_SELECT + " ORDER BY s.id LIMIT ?";

	private static final String APPROVED_REF_NEXT_PAGE =
			APPROVED_REF_SELECT + " AND s.id > ? ORDER BY s.id LIMIT ?";

	private final JdbcClient jdbc;

	/** 세션은 {@code play} 스키마에 있다 — JOIN 이 아니라 계약으로 묻는다 (§5.3, ADR-0003). */
	private final PinnedStoryVersions pinned;

	public PublishedStoryTexts(@Qualifier("catalogDataSource") DataSource catalogDataSource,
			PinnedStoryVersions pinned) {
		this.jdbc = JdbcClient.create(catalogDataSource);
		this.pinned = pinned;
	}

	/**
	 * 승인된 UGC 한 쪽 (page).
	 *
	 * <p><b>id 순으로 준다.</b> 회차마다 순서가 흔들리면 어디까지 봤는지가 의미를 잃는다.
	 *
	 * @param limit 한 번에 볼 작품 수
	 * @param after 이 id 다음부터. 첫 쪽은 {@code null}
	 */
	@Transactional(value = "catalogTransactionManager", readOnly = true)
	public List<ApprovedStory> approvedPage(int limit, UUID after) {
		// 첫 쪽과 이어지는 쪽을 나눈 것은 의도다 — null 을 UUID 자리에 바인딩하면 드라이버가
		// 타입을 정하지 못하고, CAST 로 덮으면 그 규칙이 SQL 안에 숨는다.
		JdbcClient.StatementSpec spec = (after == null)
				? this.jdbc.sql(APPROVED_FIRST_PAGE).params(limit)
				: this.jdbc.sql(APPROVED_NEXT_PAGE).params(after, limit);

		List<Scanned> scanned = spec
				.query((rs, rowNum) -> new Scanned(rs.getObject("id", UUID.class),
						rs.getString("visibility"), rs.getObject("current_version_id", UUID.class),
						gather(texts(rs.getString("title"), rs.getString("short_desc"),
								rs.getString("world_intro"), rs.getString("world_prompt")),
								declaredNamesOf(rs.getString("state_schema")))))
				.list();

		Map<UUID, Set<String>> byStory = new HashMap<>();
		Map<UUID, Set<String>> byVersion = new HashMap<>();
		scanned.forEach(story -> {
			byStory.put(story.storyId(), story.texts());
			byVersion.put(story.versionId(), story.texts());
		});

		appendPinnedVersionTexts(byStory, byVersion);
		appendVersionTexts(byVersion);

		return scanned.stream().map(story -> new ApprovedStory(story.storyId(), story.visibility(),
				story.versionId(), List.copyOf(story.texts()))).toList();
	}

	/**
	 * 승인된 UGC 의 <b>id 와 공개 범위만</b> (R8.11, B-59).
	 *
	 * <p>샘플링은 <b>무엇이 쓰였는지를 보지 않는다</b> — 뽑는 일과 읽는 일은 다르며, 뽑기
	 * 위해 원문을 전부 실어 오면 큐에 올리지도 않을 작품의 본문까지 나른다.
	 */
	@Transactional(value = "catalogTransactionManager", readOnly = true)
	public List<ApprovedRef> approvedRefs(int limit, UUID after) {
		JdbcClient.StatementSpec spec = (after == null)
				? this.jdbc.sql(APPROVED_REF_FIRST_PAGE).params(limit)
				: this.jdbc.sql(APPROVED_REF_NEXT_PAGE).params(after, limit);

		return spec.query((rs, rowNum) -> new ApprovedRef(rs.getObject("id", UUID.class),
				rs.getString("visibility"))).list();
	}

	/**
	 * 진행 중 세션이 붙들고 있는 옛 버전의 문구를 얹는다 (I-4, §13-80).
	 *
	 * <p><b>같은 작품의 자리에 넣는다.</b> 재스캔이 내리는 것은 버전이 아니라 작품이며 (R8.9 와
	 * 같은 자리), 옛 버전에서 걸린 것도 그 작품이 지금 내보내고 있는 문장이다.
	 *
	 * <p><b>현재 버전은 빼고 묻는다.</b> 대부분의 세션은 현재 버전에 붙어 있으므로 거르지 않으면
	 * 같은 버전을 두 번 읽는다.
	 */
	private void appendPinnedVersionTexts(Map<UUID, Set<String>> byStory,
			Map<UUID, Set<String>> byVersion) {
		if (byStory.isEmpty()) {
			return;
		}
		Set<UUID> olderVersions = new LinkedHashSet<>(this.pinned.pinnedVersionsOf(byStory.keySet()));
		olderVersions.removeAll(byVersion.keySet());
		if (olderVersions.isEmpty()) {
			return;
		}
		this.jdbc
				.sql("SELECT id, story_id, world_prompt, state_schema::text AS state_schema "
						+ "FROM story_version WHERE id IN (:ids)")
				.param("ids", List.copyOf(olderVersions))
				.query((java.sql.ResultSet rs) -> {
					// 그 사이에 작품이 내려갔거나 승인이 풀렸으면 이 쪽의 대상이 아니다.
					Set<String> texts = byStory.get(rs.getObject("story_id", UUID.class));
					if (texts == null) {
						return;
					}
					texts.addAll(texts(rs.getString("world_prompt")));
					texts.addAll(declaredNamesOf(rs.getString("state_schema")));
					byVersion.put(rs.getObject("id", UUID.class), texts);
				});
	}

	/**
	 * 인물 · 챕터 · 엔딩의 문구를 얹는다 (R8.5, R8.11).
	 *
	 * <p><b>인물도 작성자가 쓴 값이다</b> (§13-80). 페르소나는 매 턴 모델에게 들어가고, 이름과
	 * 한 줄 소개는 타인의 상세 화면에 뜬다 (I-8) — 제출 검수가 거는 것과 같은 셋이다 (§13-75).
	 *
	 * <p><b>작품마다 따로 묻지 않는다.</b> 배치가 한 쪽에 담는 작품 수만큼 조회가 늘면, 늘어난
	 * 것은 정확도가 아니라 DB 왕복이다. 버전이 여럿이어도 조회 수는 그대로다.
	 */
	private void appendVersionTexts(Map<UUID, Set<String>> byVersion) {
		if (byVersion.isEmpty()) {
			return;
		}
		List<UUID> versionIds = List.copyOf(byVersion.keySet());

		this.jdbc.sql("SELECT story_version_id, title, summary_seed FROM chapter_def "
						+ "WHERE story_version_id IN (:ids)")
				.param("ids", versionIds)
				.query((java.sql.ResultSet rs) -> collect(byVersion, rs,
						texts(rs.getString("title"), rs.getString("summary_seed"))));

		this.jdbc.sql("SELECT story_version_id, label, epilogue_text FROM ending_def "
						+ "WHERE story_version_id IN (:ids)")
				.param("ids", versionIds)
				.query((java.sql.ResultSet rs) -> collect(byVersion, rs,
						texts(rs.getString("label"), rs.getString("epilogue_text"))));

		this.jdbc.sql("SELECT story_version_id, name, one_line, persona_prompt FROM character "
						+ "WHERE story_version_id IN (:ids)")
				.param("ids", versionIds)
				.query((java.sql.ResultSet rs) -> collect(byVersion, rs, texts(rs.getString("name"),
						rs.getString("one_line"), rs.getString("persona_prompt"))));
	}

	/**
	 * {@code state_schema} 가 선언한 이름 (R4.1, §13-80).
	 *
	 * <p><b>작성자가 쓴 문자열이고 프롬프트로 나간다.</b> 플래그는 한 번 서면 매 턴
	 * {@code GAME_STATE} 레이어에 실리고 (§13-76), 호감도 키도 같은 자리에 실린다. 짧은 라벨을
	 * 본문과 다르게 보지 않는 것은 §13-75 가 정한 그대로다 — 판정이 둘이 되면 무른 쪽이 곧
	 * 길이 된다.
	 *
	 * <p><b>호감도 키가 인물 이름과 같다는 사실에 기대지 않는다.</b> 지금은 발행이 그렇게 쓰지만
	 * (원고의 인물 이름이 그대로 키가 된다), 그 일치가 깨지는 날 조용히 빠지는 것이 바로 이
	 * 이슈가 고치는 종류의 구멍이다. 겹치면 같은 문자열이 두 번 들어올 뿐이고 검사 결과는 같다.
	 *
	 * <p><b>읽지 못하면 예외가 올라간다.</b> 빈 목록으로 대신하면 못 읽은 작품이 <b>깨끗하다</b>
	 * 로 지나간다 — 세이프티에서 fail-open 은 장애가 곧 검수 우회다 (ADR-0002 와 같은 판단).
	 */
	private static List<String> declaredNamesOf(String stateSchemaJson) {
		if (stateSchemaJson == null || stateSchemaJson.isBlank()) {
			return List.of();
		}
		JsonNode schema = JSON.readTree(stateSchemaJson);
		List<String> names = new ArrayList<>();
		schema.path("affinity").propertyNames().forEach(names::add);
		for (JsonNode flag : schema.path("flags")) {
			if (flag.isString() && !flag.asString().isBlank()) {
				names.add(flag.asString());
			}
		}
		return names;
	}

	private static void collect(Map<UUID, Set<String>> byVersion, java.sql.ResultSet rs,
			List<String> values) throws java.sql.SQLException {
		Set<String> texts = byVersion.get(rs.getObject("story_version_id", UUID.class));
		if (texts != null) {
			texts.addAll(values);
		}
	}

	/**
	 * 한 작품이 검사받을 문자열.
	 *
	 * <p><b>같은 문장을 두 번 검사하지 않는다.</b> 버전이 늘어도 대부분의 문구는 그대로이고,
	 * 재스캔이 남기는 것은 카테고리뿐이므로 (R8.7) 몇 번 나왔는지는 결과를 바꾸지 않는다.
	 */
	private static Set<String> gather(List<String> first, List<String> second) {
		Set<String> texts = new LinkedHashSet<>(first);
		texts.addAll(second);
		return texts;
	}

	/** 비어 있는 것은 검사할 것이 없다. */
	private static List<String> texts(String... values) {
		List<String> present = new ArrayList<>(values.length);
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				present.add(value);
			}
		}
		return present;
	}

	/** 한 쪽을 모으는 동안의 자리. 쌓는 사이에 값이 늘어나므로 공개 record 와 나눈다. */
	private record Scanned(UUID storyId, String visibility, UUID versionId, Set<String> texts) {
	}

	/**
	 * 재스캔 대상 한 건.
	 *
	 * @param visibility 공개 범위. 사후 장치가 이 값에 따라 달라진다 (§13-12)
	 * @param versionId <b>현재 버전</b>이다. 진행 중 세션이 붙든 옛 버전의 문구도
	 *     {@code texts} 에 함께 들어 있다 (§13-80)
	 * @param texts 검사할 문자열들. <b>순서에 의미가 없다</b> — 남기는 것은 카테고리뿐이다
	 */
	public record ApprovedStory(UUID storyId, String visibility, UUID versionId, List<String> texts) {
	}

	/** 샘플링이 보는 것 — <b>무엇이 쓰였는지는 뽑은 뒤에 사람이 본다.</b> */
	public record ApprovedRef(UUID storyId, String visibility) {
	}
}

package com.neowadaeum.catalog.publish;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 게시된 UGC 의 <b>검수 대상 원문</b>을 읽는다 (R9.4, B-59).
 *
 * <p><b>현재 버전만 본다.</b> 재스캔이 묻는 것은 "지금 사람들이 읽고 있는 것이 오늘 기준으로도
 * 괜찮은가"이고, 옛 버전은 아무도 새로 읽지 않는다 — 진행 중 세션이 보는 것뿐이며 그것은
 * 이미 지나간 판정이다.
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

	private static final String APPROVED_SELECT = """
			SELECT s.id, s.visibility, s.current_version_id, s.title, s.short_desc,
			       s.world_intro, v.world_prompt
			FROM story s
			JOIN story_version v ON v.id = s.current_version_id
			WHERE s.review_status = 'approved' AND s.author_type = 'user'
			""";

	private static final String APPROVED_FIRST_PAGE = APPROVED_SELECT + " ORDER BY s.id LIMIT ?";

	private static final String APPROVED_NEXT_PAGE =
			APPROVED_SELECT + " AND s.id > ? ORDER BY s.id LIMIT ?";

	private final JdbcClient jdbc;

	public PublishedStoryTexts(@Qualifier("catalogDataSource") DataSource catalogDataSource) {
		this.jdbc = JdbcClient.create(catalogDataSource);
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

		List<ApprovedStory> stories = spec
				.query((rs, rowNum) -> new ApprovedStory(rs.getObject("id", UUID.class),
						rs.getString("visibility"), rs.getObject("current_version_id", UUID.class),
						new ArrayList<>(texts(rs.getString("title"), rs.getString("short_desc"),
								rs.getString("world_intro"), rs.getString("world_prompt")))))
				.list();

		appendVersionTexts(stories);
		return stories;
	}

	/**
	 * 챕터와 엔딩의 문구를 얹는다 (R8.5).
	 *
	 * <p><b>작품마다 따로 묻지 않는다.</b> 배치가 한 쪽에 담는 작품 수만큼 조회가 늘면, 늘어난
	 * 것은 정확도가 아니라 DB 왕복이다.
	 */
	private void appendVersionTexts(List<ApprovedStory> stories) {
		if (stories.isEmpty()) {
			return;
		}
		Map<UUID, List<String>> byVersion = new HashMap<>();
		stories.forEach(story -> byVersion.put(story.versionId(), story.texts()));
		List<UUID> versionIds = stories.stream().map(ApprovedStory::versionId).toList();

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
	}

	private static void collect(Map<UUID, List<String>> byVersion, java.sql.ResultSet rs,
			List<String> values) throws java.sql.SQLException {
		List<String> texts = byVersion.get(rs.getObject("story_version_id", UUID.class));
		if (texts != null) {
			texts.addAll(values);
		}
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

	/**
	 * 재스캔 대상 한 건.
	 *
	 * @param visibility 공개 범위. 사후 장치가 이 값에 따라 달라진다 (§13-12)
	 * @param texts 검사할 문자열들. <b>순서에 의미가 없다</b> — 남기는 것은 카테고리뿐이다
	 */
	public record ApprovedStory(UUID storyId, String visibility, UUID versionId, List<String> texts) {
	}
}

package com.neowadaeum.catalog.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 작품 버전 조회 파사드 (S-9-1).
 *
 * <p><b>모듈 간 호출은 파사드로만 한다</b> (§5.4). {@code play} 는 이 클래스를 부르고
 * {@code catalog} 의 테이블·DataSource 를 직접 잡지 않는다.
 *
 * <p><b>스키마 간 JOIN 을 하지 않는다</b> (§5.3). 여기서 읽는 세 테이블은 전부 catalog 스키마
 * 안에 있고, {@code play} 쪽 데이터와는 애플리케이션 레벨에서만 만난다.
 */
@Component
public class StoryVersionFacade {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final JdbcClient jdbc;

	public StoryVersionFacade(@Qualifier("catalogDataSource") DataSource catalogDataSource) {
		this.jdbc = JdbcClient.create(catalogDataSource);
	}

	/**
	 * 판정에 필요한 한 벌을 통째로 읽는다.
	 *
	 * @return 버전이 없으면 비어 있다 — 세션이 사라진 버전을 가리키는 상황이며 호출자가 판단한다
	 */
	public Optional<StoryVersionView> findByVersionId(UUID storyVersionId) {
		Optional<Object[]> version = this.jdbc.sql("""
						SELECT state_schema, choice_policy FROM story_version WHERE id = ?
						""")
				.param(storyVersionId)
				.query((rs, rowNum) -> new Object[] { rs.getString("state_schema"), rs.getString("choice_policy") })
				.optional();

		if (version.isEmpty()) {
			return Optional.empty();
		}

		return Optional.of(new StoryVersionView(
				storyVersionId,
				readJson((String) version.get()[0]),
				readJson((String) version.get()[1]),
				chapters(storyVersionId),
				endings(storyVersionId)));
	}

	private List<StoryVersionView.ChapterView> chapters(UUID storyVersionId) {
		return this.jdbc.sql("""
						SELECT chapter_no, title, entry_condition, min_turns, max_turns
						FROM chapter_def WHERE story_version_id = ? ORDER BY chapter_no
						""")
				.param(storyVersionId)
				.query((rs, rowNum) -> new StoryVersionView.ChapterView(
						rs.getInt("chapter_no"),
						rs.getString("title"),
						readJson(rs.getString("entry_condition")),
						rs.getInt("min_turns"),
						rs.getInt("max_turns")))
				.list();
	}

	private List<StoryVersionView.EndingView> endings(UUID storyVersionId) {
		return this.jdbc.sql("""
						SELECT id, ending_no, label, condition, is_secret, is_default
						FROM ending_def WHERE story_version_id = ? ORDER BY ending_no
						""")
				.param(storyVersionId)
				.query((rs, rowNum) -> new StoryVersionView.EndingView(
						rs.getObject("id", UUID.class),
						rs.getInt("ending_no"),
						rs.getString("label"),
						readJson(rs.getString("condition")),
						rs.getBoolean("is_secret"),
						rs.getBoolean("is_default")))
				.list();
	}

	/** {@code jsonb} 는 JDBC 로 {@code PGobject} 로 오므로 텍스트로 받아 파싱한다. */
	private static JsonNode readJson(String raw) {
		return (raw != null) ? JSON.readTree(raw) : null;
	}
}

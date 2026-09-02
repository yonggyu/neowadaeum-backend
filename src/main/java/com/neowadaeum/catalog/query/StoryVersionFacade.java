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
 * <p><b>스키마 간 JOIN 을 하지 않는다</b> (§5.3). 여기서 읽는 테이블은 전부 catalog 스키마
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
	 * <p><b>{@code story} 를 조인해 제목을 함께 가져온다</b> (#259). 조인 상대가 catalog 스키마
	 * 안이므로 §5.3 의 "스키마 간 JOIN 금지"에 걸리지 않으며, <b>왕복이 늘지 않는다</b> — 매 턴
	 * 제목만 따로 읽으면 §15 예산에 조회가 하나 얹힌다.
	 *
	 * <p>제목은 <b>버전이 가리키는 작품</b>의 것이다 ({@code story_version.story_id}). 세션이 고정한
	 * 버전으로 들어오므로 (I-4) 다른 작품의 제목이 섞일 자리가 없다.
	 *
	 * @return 버전이 없으면 비어 있다 — 세션이 사라진 버전을 가리키는 상황이며 호출자가 판단한다
	 */
	public Optional<StoryVersionView> findByVersionId(UUID storyVersionId) {
		Optional<Object[]> version = this.jdbc.sql("""
						SELECT v.world_prompt, v.state_schema, v.choice_policy, s.title
						FROM story_version v JOIN story s ON s.id = v.story_id
						WHERE v.id = ?
						""")
				.param(storyVersionId)
				.query((rs, rowNum) -> new Object[] { rs.getString("world_prompt"), rs.getString("state_schema"),
						rs.getString("choice_policy"), rs.getString("title") })
				.optional();

		if (version.isEmpty()) {
			return Optional.empty();
		}

		return Optional.of(new StoryVersionView(
				storyVersionId,
				(String) version.get()[3],
				(String) version.get()[0],
				readJson((String) version.get()[1]),
				readJson((String) version.get()[2]),
				characters(storyVersionId),
				chapters(storyVersionId),
				endings(storyVersionId)));
	}

	/**
	 * 프롬프트의 CHARACTER 레이어 (§5.1, §4.4).
	 *
	 * <p><b>{@code display_order} 로 정렬한다.</b> 순서가 흔들리면 프롬프트가 매 턴 달라지고,
	 * 골든 파일이 그것을 잡되 <b>원인이 프롬프트 변경인지 조회 순서인지 구분되지 않는다.</b>
	 * 컬럼에 유니크 제약이 있으므로 정렬은 결정론이다.
	 */
	private List<StoryVersionView.CharacterView> characters(UUID storyVersionId) {
		return this.jdbc.sql("""
						SELECT name, persona_prompt FROM character
						WHERE story_version_id = ? ORDER BY display_order
						""")
				.param(storyVersionId)
				.query((rs, rowNum) -> new StoryVersionView.CharacterView(
						rs.getString("name"), rs.getString("persona_prompt")))
				.list();
	}

	/**
	 * 세션 시작 시 고정할 작품 버전 (§4.2, I-4).
	 *
	 * <p>{@code story.current_version_id} 를 읽는다. 세션은 이 값을 <b>복사해 들고</b> 가므로,
	 * 나중에 새 버전이 발행돼도 진행 중 세션은 영향받지 않는다 (R2.1, R8.8).
	 *
	 * @return 게시된 작품이 아니거나 버전이 없으면 비어 있다
	 */
	public Optional<UUID> findCurrentVersionId(UUID storyId) {
		return this.jdbc.sql("SELECT current_version_id FROM story WHERE id = ?")
				.param(storyId)
				.query((rs, rowNum) -> rs.getObject("current_version_id", UUID.class))
				.optional()
				.filter(java.util.Objects::nonNull);
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

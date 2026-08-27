package com.neowadaeum.catalog.query;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 라이브러리가 읽는 작품 목록 (§13.2, B-15).
 *
 * <p><b>모듈 간 호출은 파사드로만 한다</b> (§5.4). {@code play} 가 이 클래스를 부르고 catalog 의
 * 테이블을 직접 잡지 않는다.
 *
 * <p><b>노출 조건이 이 클래스의 핵심이다.</b> R2.3 은 사용자 작품이 {@code approved} 이고
 * {@code private} 가 아닐 때만 타인에게 보인다고 정하고, I-8 은 <b>검수 승인 없이 어떤 경로로도
 * 노출되지 않는다</b>고 못박는다. 그 조건을 <b>SQL 한 곳에</b> 둔다 — 호출부마다 붙이면 언젠가
 * 하나가 빠지고, 빠진 그 경로가 유출 경로가 된다.
 *
 * <p><b>작품 수만큼 조회하지 않는다</b> (§15 — p95 300ms). 한 쪽을 읽고, 그 쪽의 작품 id 로
 * 장르를 <b>한 번에</b> 읽는다. 카드마다 장르를 물으면 20장이 21번의 조회가 된다.
 */
@Component
public class StoryCatalogFacade {

	/**
	 * <b>[결정 필요]</b> {@code isNew} 의 기준. 원문에 정의가 없다.
	 *
	 * <p>2주를 기본 채택안으로 둔다 — 라이브러리를 주 단위로 도는 사용자에게 "새로 생긴 것"이
	 * 최소 한 번은 새것으로 보이는 길이다. {@code docs/corrections.md} §13-25 에 올렸다.
	 */
	private static final Duration NEW_WINDOW = Duration.ofDays(14);

	/** 한 쪽의 기본 크기. 계약의 {@code Limit} 파라미터 기본값과 같다. */
	static final int DEFAULT_LIMIT = 20;

	/** 계약의 {@code Limit} 상한. 더 큰 값을 받아도 여기서 잘린다. */
	static final int MAX_LIMIT = 50;

	private final JdbcClient jdbc;

	private final Clock clock;

	public StoryCatalogFacade(@Qualifier("catalogDataSource") DataSource catalogDataSource, Clock clock) {
		this.jdbc = JdbcClient.create(catalogDataSource);
		this.clock = clock;
	}

	/** 화면이 보여 줄 장르 목록. {@code display_order} 가 유일하므로 순서가 결정론이다. */
	public List<GenreView> genres() {
		return this.jdbc.sql("SELECT key, label FROM genre ORDER BY display_order")
				.query((rs, rowNum) -> new GenreView(rs.getString("key"), rs.getString("label")))
				.list();
	}

	/**
	 * <b>공식 카탈로그가 실제로 쓰는 장르만</b> (§13.2, B-15).
	 *
	 * <p>라이브러리 개요가 장르 섹션을 전부 만들면 비어 있는 섹션이 화면에 남는다. 여기서
	 * 걸러 두면 <b>섹션 수만큼의 조회</b>도 함께 준다 — 장르가 늘어도 빈 섹션은 늘지 않는다.
	 */
	public List<String> officialGenreKeys() {
		return this.jdbc.sql("""
						SELECT DISTINCT g.key, g.display_order
						FROM story_genre sg
						JOIN genre g ON g.id = sg.genre_id
						JOIN story s ON s.id = sg.story_id
						WHERE s.review_status = 'approved'
						  AND s.visibility <> 'private'
						  AND s.published_at IS NOT NULL
						  AND s.current_version_id IS NOT NULL
						  AND s.author_type = 'official'
						ORDER BY g.display_order
						""")
				.query((rs, rowNum) -> rs.getString("key"))
				.list();
	}

	/**
	 * 이어하기 카드가 필요한 작품 정보를 <b>한 번에</b> 읽는다 (§13.2, R13.2).
	 *
	 * <p>세션마다 물으면 이어하기 다섯 개가 열 번의 조회가 된다. 챕터 제목은 몇 개 되지 않으므로
	 * 그 버전의 것을 통째로 담아 보내고, 어느 챕터인지는 <b>세션이 안다</b> — 그 값은 play 의
	 * 것이고 catalog 는 알 필요가 없다 (§5.3).
	 *
	 * @param storyVersionIds 세션이 고정한 버전들 (I-4)
	 * @return 버전 id 로 찾는 지도. 사라진 버전은 들어 있지 않다
	 */
	public Map<UUID, StoryBriefView> briefs(List<UUID> storyVersionIds) {
		if (storyVersionIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, List<StoryBriefView.ChapterTitle>> chapters = new HashMap<>();
		this.jdbc.sql("""
						SELECT story_version_id, chapter_no, title FROM chapter_def
						WHERE story_version_id IN (:ids) ORDER BY chapter_no
						""")
				.param("ids", storyVersionIds)
				.query((rs, rowNum) -> Map.entry(rs.getObject("story_version_id", UUID.class),
						new StoryBriefView.ChapterTitle(rs.getInt("chapter_no"), rs.getString("title"))))
				.list()
				.forEach(entry -> chapters.computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
						.add(entry.getValue()));

		Map<UUID, StoryBriefView> byVersion = new HashMap<>();
		this.jdbc.sql("""
						SELECT v.id AS version_id, s.id AS story_id, s.title, s.cover_url
						FROM story_version v JOIN story s ON s.id = v.story_id
						WHERE v.id IN (:ids)
						""")
				.param("ids", storyVersionIds)
				.query((rs, rowNum) -> {
					UUID versionId = rs.getObject("version_id", UUID.class);
					return new StoryBriefView(versionId, rs.getObject("story_id", UUID.class),
							rs.getString("title"), rs.getString("cover_url"),
							chapters.getOrDefault(versionId, List.of()));
				})
				.list()
				.forEach(brief -> byVersion.put(brief.storyVersionId(), brief));
		return byVersion;
	}

	/**
	 * 섹션 한 쪽을 읽는다.
	 *
	 * <p>정렬은 {@code (published_at DESC, id DESC)} 이며 커서도 그 짝이다. 발행 시각만으로는
	 * 같은 시각의 작품들이 쪽 경계에서 <b>중복되거나 사라진다</b> — 시드처럼 한 번에 넣은
	 * 데이터에서 실제로 일어난다.
	 *
	 * @param limit  1 이상 {@value #MAX_LIMIT} 이하로 잘린다
	 * @param cursor 이전 쪽의 {@link StoryPage#nextCursor()}. 처음이면 {@code null}
	 */
	public StoryPage cards(LibrarySectionKey section, String cursor, Integer limit) {
		int size = Math.clamp((limit != null) ? limit : DEFAULT_LIMIT, 1, MAX_LIMIT);
		Optional<Cursor> after = Cursor.parse(cursor);

		// 한 개 더 읽어 "다음 쪽이 있는가"를 별도 count 없이 안다.
		List<Row> rows = this.jdbc.sql(sql(section, after.isPresent()))
				.params(params(section, after, size + 1))
				.query((rs, rowNum) -> new Row(rs.getObject("id", UUID.class), rs.getString("title"),
						rs.getString("cover_url"), rs.getString("short_desc"), rs.getString("author_type"),
						rs.getTimestamp("published_at").toInstant()))
				.list();

		boolean more = rows.size() > size;
		List<Row> page = more ? rows.subList(0, size) : rows;
		Map<UUID, List<String>> genresByStory = genresOf(page.stream().map(Row::id).toList());
		Instant newSince = this.clock.instant().minus(NEW_WINDOW);

		List<StoryCardView> cards = new ArrayList<>(page.size());
		for (Row row : page) {
			cards.add(new StoryCardView(row.id(), row.title(), row.coverUrl(),
					genresByStory.getOrDefault(row.id(), List.of()), row.shortDesc(),
					row.publishedAt().isAfter(newSince), row.authorType()));
		}
		return new StoryPage(cards, more ? new Cursor(page.getLast().publishedAt(), page.getLast().id()).encode()
				: null);
	}

	/**
	 * 한 쪽의 장르를 한 번에 읽는다.
	 *
	 * <p>순서는 {@code display_order} 다 — 카드마다 장르 순서가 흔들리면 같은 데이터가 매번 다르게
	 * 보인다.
	 */
	private Map<UUID, List<String>> genresOf(List<UUID> storyIds) {
		if (storyIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, List<String>> byStory = new HashMap<>();
		this.jdbc.sql("""
						SELECT sg.story_id, g.key
						FROM story_genre sg JOIN genre g ON g.id = sg.genre_id
						WHERE sg.story_id IN (:ids)
						ORDER BY g.display_order
						""")
				.param("ids", storyIds)
				.query((rs, rowNum) -> Map.entry(rs.getObject("story_id", UUID.class), rs.getString("key")))
				.list()
				.forEach(entry -> byStory.computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
						.add(entry.getValue()));
		return byStory;
	}

	/**
	 * <b>노출 조건이 여기 한 곳에 있다</b> (R2.3, I-8).
	 *
	 * <p>공식 작품도 {@code approved} 를 요구한다 — 지금은 전부 승인 상태지만, 조건을 종류별로
	 * 다르게 두면 <b>공식이라는 이유로 검수를 건너뛰는 경로</b>가 생긴다.
	 */
	private static String sql(LibrarySectionKey section, boolean afterCursor) {
		String base = """
				SELECT s.id, s.title, s.cover_url, s.short_desc, s.author_type, s.published_at
				FROM story s
				WHERE s.review_status = 'approved'
				  AND s.visibility <> 'private'
				  AND s.published_at IS NOT NULL
				  AND s.current_version_id IS NOT NULL
				""";
		base += switch (section.kind()) {
			// R13.1 — 장르 섹션도 공식만 담는다. 사용자 작품은 community 하나로 모인다.
			case RECOMMENDED, GENRE -> "  AND s.author_type = 'official'\n";
			case COMMUNITY -> "  AND s.author_type = 'user'\n";
		};
		if (section.kind() == LibrarySectionKey.Kind.GENRE) {
			base += """
					  AND EXISTS (SELECT 1 FROM story_genre sg JOIN genre g ON g.id = sg.genre_id
					              WHERE sg.story_id = s.id AND g.key = :genreKey)
					""";
		}
		if (afterCursor) {
			base += "  AND (s.published_at, s.id) < (:cursorAt, :cursorId)\n";
		}
		return base + "ORDER BY s.published_at DESC, s.id DESC\nLIMIT :size";
	}

	private static Map<String, Object> params(LibrarySectionKey section, Optional<Cursor> after, int size) {
		Map<String, Object> params = new HashMap<>();
		params.put("size", size);
		if (section.kind() == LibrarySectionKey.Kind.GENRE) {
			params.put("genreKey", section.genreKey());
		}
		after.ifPresent(cursor -> {
			params.put("cursorAt", java.sql.Timestamp.from(cursor.publishedAt()));
			params.put("cursorId", cursor.storyId());
		});
		return params;
	}

	private record Row(UUID id, String title, String coverUrl, String shortDesc, String authorType,
			Instant publishedAt) {
	}

	/**
	 * 커서 — 정렬 키 그대로다.
	 *
	 * <p>Base64 로 감싸는 것은 암호가 아니라 <b>표시</b>다. 클라이언트가 값을 해석하거나 만들어
	 * 보내는 것을 전제하지 않는다는 뜻이며, 형식이 바뀌어도 계약이 흔들리지 않는다.
	 */
	private record Cursor(Instant publishedAt, UUID storyId) {

		String encode() {
			return Base64.getUrlEncoder().withoutPadding().encodeToString(
					"%d:%s".formatted(this.publishedAt.toEpochMilli(), this.storyId)
							.getBytes(StandardCharsets.UTF_8));
		}

		/** 해석되지 않는 커서는 <b>처음부터</b>로 본다 — 500 을 내는 것보다 낫다. */
		static Optional<Cursor> parse(String raw) {
			if (raw == null || raw.isBlank()) {
				return Optional.empty();
			}
			try {
				String[] parts = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8).split(":");
				return Optional.of(new Cursor(Instant.ofEpochMilli(Long.parseLong(parts[0])),
						UUID.fromString(parts[1])));
			}
			catch (RuntimeException ex) {
				return Optional.empty();
			}
		}
	}
}

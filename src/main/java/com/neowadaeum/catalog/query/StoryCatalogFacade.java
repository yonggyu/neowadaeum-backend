package com.neowadaeum.catalog.query;

import com.neowadaeum.common.spi.StoryReviewTimes;
import com.neowadaeum.common.spi.StoryReviewTimesQuery;
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

	/**
	 * R2.8 — 이보다 표본이 적으면 도달률을 내보내지 않는다.
	 *
	 * <p>비율이 무의미하기도 하지만, <b>UGC 작품에서는 개인 식별로 이어질 수 있다</b> —
	 * 완주자가 셋인 작품의 도달률은 특정인의 선택을 드러낸다.
	 */
	static final int REACH_RATE_MIN_SAMPLE = 50;

	private final JdbcClient jdbc;

	private final Clock clock;

	/**
	 * 검수 시각 (§13-57, #290).
	 *
	 * <p><b>{@code story_review} 를 직접 읽지 않는다.</b> 같은 스키마에 있지만 그 표를 쓰는
	 * 모듈은 authoring 하나이고, 읽는 길을 여기서 새로 파면 <b>같은 표를 두 모듈이 각자
	 * 해석하게</b> 된다 — 회차를 가르는 규칙이 둘이 되는 순간 목록과 상세가 다른 날짜를 말한다.
	 */
	private final StoryReviewTimesQuery reviewTimes;

	public StoryCatalogFacade(@Qualifier("catalogDataSource") DataSource catalogDataSource, Clock clock,
			StoryReviewTimesQuery reviewTimes) {
		this.jdbc = JdbcClient.create(catalogDataSource);
		this.clock = clock;
		this.reviewTimes = reviewTimes;
	}

	/** 화면이 보여 줄 장르 목록. {@code display_order} 가 유일하므로 순서가 결정론이다. */
	public List<GenreView> genres() {
		return this.jdbc.sql("SELECT key, label FROM genre ORDER BY display_order")
				.query((rs, rowNum) -> new GenreView(rs.getString("key"), rs.getString("label")))
				.list();
	}

	/**
	 * 내가 만든 작품 (§13.7, R13.4, B-36).
	 *
	 * <p><b>노출 조건을 걸지 않는다.</b> 작성자는 자기 작품을 상태와 무관하게 본다 — 오히려
	 * {@code pending} · {@code rejected} 야말로 이 화면이 보여 줘야 할 것이다 (R8.7).
	 *
	 * <p><b>{@code rejectReasons} 는 아직 비어 있다.</b> 출처가 {@code story_review} 이고 그 표는
	 * B-10 이다. 자리를 비워 두는 것과 없는 것은 다르다 — 계약이 요구하는 필드이며, 채우는 쪽이
	 * 생기면 여기만 바뀐다.
	 *
	 * <p><b>지운 작품은 여기서도 빠진다</b> (§13-58). 이 목록이 노출 조건을 걸지 않는 유일한
	 * 예외 자리이므로, 걸러 주지 않으면 <b>작성자에게만 삭제가 취소된 것처럼 보인다</b> —
	 * 사용자에게 '삭제됨'이라고 말한 화면이 지운 작품을 계속 들고 있게 된다.
	 *
	 * @param playerRef 작성자. {@code author_ref} 와 대조한다 (I-3 — catalog 는 이 값만 안다)
	 */
	public MyStoryPage mine(UUID playerRef, String cursor, Integer limit) {
		int size = Math.clamp((limit != null) ? limit : DEFAULT_LIMIT, 1, MAX_LIMIT);
		Optional<Cursor> after = Cursor.parse(cursor);

		Map<String, Object> params = new HashMap<>();
		params.put("author", playerRef);
		params.put("size", size + 1);
		after.ifPresent(cur -> {
			params.put("cursorAt", java.sql.Timestamp.from(cur.publishedAt()));
			params.put("cursorId", cur.storyId());
		});

		String sql = """
				SELECT s.id, s.title, s.cover_url, s.short_desc, s.author_type, s.created_at AS ordered_at,
				       s.visibility, s.review_status, s.published_at
				FROM story s
				WHERE s.author_ref = :author
				  AND s.review_status <> 'deleted'
				""";
		if (after.isPresent()) {
			sql += "  AND (s.created_at, s.id) < (:cursorAt, :cursorId)\n";
		}
		sql += "ORDER BY s.created_at DESC, s.id DESC\nLIMIT :size";

		List<MyStoryRow> rows = this.jdbc.sql(sql).params(params)
				.query((rs, rowNum) -> new MyStoryRow(rs.getObject("id", UUID.class), rs.getString("title"),
						rs.getString("cover_url"), rs.getString("visibility"), rs.getString("review_status"),
						rs.getTimestamp("ordered_at").toInstant()))
				.list();

		boolean more = rows.size() > size;
		List<MyStoryRow> page = more ? rows.subList(0, size) : rows;

		// §15 — 줄마다 물으면 20줄이 21번의 조회가 된다. 쪽 전체를 한 번에 묻는다 (#290).
		Map<UUID, StoryReviewTimes> times = this.reviewTimes
				.findByStoryIds(page.stream().map(MyStoryRow::id).toList());

		List<MyStoryView> stories = page.stream().map(row -> {
			var when = times.getOrDefault(row.id(), StoryReviewTimes.NONE);
			return new MyStoryView(row.id(), row.title(), row.coverUrl(), row.visibility(),
					reviewStatusFor(row.reviewStatus()), List.of(), row.createdAt(),
					when.submittedAt(), when.reviewedAt());
		}).toList();

		return new MyStoryPage(stories, more
				? new Cursor(page.getLast().createdAt(), page.getLast().id()).encode() : null);
	}

	/**
	 * <b>{@code auto_rejected} 는 사용자에게 {@code rejected} 로 보인다</b> (§13-9).
	 *
	 * <p>자동 검수인지 사람이 본 것인지는 내부 기록이며, 그것을 알리면 어느 쪽을 통과하면
	 * 되는지에 대한 단서가 된다.
	 */
	private static String reviewStatusFor(String stored) {
		return "auto_rejected".equals(stored) ? "rejected" : stored;
	}

	private record MyStoryRow(UUID id, String title, String coverUrl, String visibility, String reviewStatus,
			java.time.Instant createdAt) {
	}

	/**
	 * 엔딩 도달률 (R2.7, R2.8, I-20, #161).
	 *
	 * <p><b>계산하지 않는다.</b> 배치가 갱신한 값을 읽을 뿐이다 (I-20) — 조회 시점에 세면
	 * 인기 있는 작품일수록 엔딩 화면이 느려지고, 그것이 I-20 이 금지한 것이다.
	 *
	 * <p><b>표본이 {@value #REACH_RATE_MIN_SAMPLE} 미만이면 비어 있다</b> (R2.8). 두 가지
	 * 이유가 있다 — 비율이 무의미하고(2명 중 1명 = 50%), <b>UGC 작품에서는 개인 식별로 이어질
	 * 수 있다</b>(완주자가 셋인 작품의 도달률은 특정인의 선택을 드러낸다).
	 *
	 * @return {@code 0.0 ~ 1.0}. 집계가 없거나 표본이 적으면 비어 있다
	 */
	public Optional<Double> reachRate(UUID storyId, int endingNo) {
		return this.jdbc.sql("""
						SELECT reached_count, total_completed_count FROM ending_stat
						WHERE story_id = :storyId AND ending_no = :endingNo
						""")
				.param("storyId", storyId)
				.param("endingNo", endingNo)
				.query((rs, rowNum) -> new long[] { rs.getLong("reached_count"),
						rs.getLong("total_completed_count") })
				.optional()
				.filter(counts -> counts[1] >= REACH_RATE_MIN_SAMPLE)
				.map(counts -> (double) counts[0] / counts[1]);
	}

	/**
	 * 이어하기 판정에 필요한 작품 쪽 사실 (§13.4, §4.7, B-17).
	 *
	 * <p><b>노출 조건을 걸지 않는다.</b> 이 조회는 <b>이미 시작한 세션</b>이 어떤 상태인지 묻는
	 * 것이고, 정지된 작품(R8.10)이야말로 답해야 할 경우다 — 가리면 {@code story_suspended} 를
	 * 낼 수 없고 사용자는 이유 없이 404 를 본다.
	 *
	 * <p><b>지워진 작품은 그 예외가 아니다</b> (§13-58). 여기서 비워 두면 {@code play} 는 그것을
	 * <b>사라진 작품</b>으로 읽고, 사라진 작품과 정지된 작품은 이미 같은 자리로 간다 —
	 * {@code STORY_SUSPENDED}(423, 읽기 전용) 다. {@code PlayTurnService} 와
	 * {@code SessionResumeService} 가 "없는 작품과 정지된 작품을 구분해 알릴 이유가 없다"고
	 * 적어 둔 바로 그 판단이며, 그래서 <b>새 에러 코드를 만들지 않았다.</b> 읽던 사람은 이미
	 * 읽은 것을 계속 볼 수 있고 다음 턴만 만들지 못한다.
	 *
	 * @return 작품이 없거나 <b>지워졌으면</b> 비어 있다
	 */
	public Optional<StoryStatusView> status(UUID storyId) {
		return this.jdbc.sql("""
						SELECT current_version_id, review_status FROM story
						WHERE id = :id AND review_status <> 'deleted'
						""")
				.param("id", storyId)
				.query((rs, rowNum) -> new StoryStatusView(rs.getObject("current_version_id", UUID.class),
						rs.getString("review_status")))
				.optional();
	}

	/**
	 * 작품 상세 (§13.3, B-16).
	 *
	 * <p><b>검수 조건은 목록과 같다</b> (R2.3, I-8) — {@code approved} 이고 발행됐고 버전이
	 * 고정된 것만이다. 이 셋이 느슨해지면 목록에서 가린 작품을 <b>id 를 아는 사람이 그대로
	 * 읽는다</b> — 가린 의미가 사라진다.
	 *
	 * <p><b>가시성만 목록보다 한 칸 넓다</b> ({@code <> 'private'}, §13-54). {@code unlisted}
	 * 는 <b>목록에 오르지 않되 링크로는 닿는</b> 상태이고, 그것이 {@code public} 과 구분되는
	 * 유일한 이유다. 여기까지 {@code public} 으로 좁히면 {@code unlisted} 가 {@code private}
	 * 과 같아지고, 세 값 중 하나가 뜻을 잃는다.
	 *
	 * <p>{@code current_version_id} 를 기준으로 읽는다. 상세는 <b>지금 시작하면 무엇을
	 * 플레이하게 되는가</b>를 보여 주는 화면이므로 세션이 고정한 버전이 아니라 최신이다 (R2.1).
	 *
	 * @return 없거나 <b>보이지 않아야 하는</b> 작품이면 비어 있다 — 둘을 구분하지 않는다
	 */
	public Optional<StoryDetailView> detail(UUID storyId) {
		Optional<DetailRow> row = this.jdbc.sql("""
						SELECT s.id, s.title, s.hero_url, s.description, s.world_intro, s.author_type,
						       s.author_ref, s.current_version_id
						FROM story s
						WHERE s.id = :storyId
						  AND s.review_status = 'approved'
						  AND s.visibility <> 'private'
						  AND s.published_at IS NOT NULL
						  AND s.current_version_id IS NOT NULL
						""")
				.param("storyId", storyId)
				.query((rs, rowNum) -> new DetailRow(rs.getString("title"), rs.getString("hero_url"),
						rs.getString("description"), rs.getString("world_intro"), rs.getString("author_type"),
						rs.getObject("author_ref", UUID.class),
						rs.getObject("current_version_id", UUID.class)))
				.optional();

		if (row.isEmpty()) {
			return Optional.empty();
		}
		DetailRow detail = row.get();
		UUID versionId = detail.currentVersionId();
		return Optional.of(new StoryDetailView(storyId, detail.title(), detail.heroUrl(),
				genresOf(List.of(storyId)).getOrDefault(storyId, List.of()), detail.description(),
				detail.worldIntro(), detail.authorType(), displayNameOf(detail.authorRef()),
				countOf("SELECT COUNT(*) FROM chapter_def WHERE story_version_id = :id", versionId),
				// R7.11 — 시크릿은 세지 않는다. 개수만으로도 존재가 새면 안 된다.
				countOf("SELECT COUNT(*) FROM ending_def WHERE story_version_id = :id AND is_secret = FALSE",
						versionId),
				charactersOf(versionId)));
	}

	/**
	 * 상세에 보이는 인물만 (§13.3).
	 *
	 * <p>{@code persona_prompt} 를 읽지 않는다 — 프롬프트의 재료이지 화면의 것이 아니다.
	 */
	private List<CharacterCardView> charactersOf(UUID storyVersionId) {
		return this.jdbc.sql("""
						SELECT id, name, role, portrait_url, one_line FROM character
						WHERE story_version_id = :id AND is_visible_in_detail = TRUE
						ORDER BY display_order
						""")
				.param("id", storyVersionId)
				.query((rs, rowNum) -> new CharacterCardView(rs.getObject("id", UUID.class),
						rs.getString("name"), rs.getString("role"), rs.getString("portrait_url"),
						rs.getString("one_line")))
				.list();
	}

	/**
	 * 작성자 표시명 — <b>상세 한 건만</b> (§13-7).
	 *
	 * <p><b>{@code playerRef} 는 여기서 끝난다.</b> 밖으로 나가는 것은 닉네임뿐이며, 프로필이
	 * 없으면 {@code null} 이다 — 식별자를 대신 내보내지 않는다 (I-3).
	 *
	 * <p><b>목록은 이것을 쓰지 않는다.</b> {@link #cards} 는 한 쪽의 작품을 한 번에 읽으므로
	 * 카드마다 이 메서드를 부르면 그대로 N+1 이 된다 (§15 — p95 300ms). 그쪽은 같은 사실을
	 * {@code author_profile} 조인으로 함께 읽는다. <b>둘을 하나로 합치지 않는 이유</b>는 읽는
	 * 단위가 다르기 때문이다 — 단건과 한 쪽은 같은 질문이 아니다.
	 */
	private String displayNameOf(UUID authorRef) {
		if (authorRef == null) {
			return null;
		}
		return this.jdbc.sql("SELECT display_name FROM author_profile WHERE player_ref = :ref")
				.param("ref", authorRef)
				.query(String.class)
				.optional()
				.orElse(null);
	}

	private int countOf(String sql, UUID versionId) {
		return this.jdbc.sql(sql).param("id", versionId).query(Integer.class).single();
	}

	private record DetailRow(String title, String heroUrl, String description, String worldIntro,
			String authorType, UUID authorRef, UUID currentVersionId) {
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
						  AND s.visibility = 'public'
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
	 * <p><b>지워진 작품의 버전은 들어 있지 않다</b> (§13-58). 이어하기 카드와 "내 세션" 목록이
	 * 이 지도로 만들어지며, 둘 다 <b>지도에 없는 세션을 조용히 뺀다</b> — 그 자리가 이미 있으므로
	 * 삭제가 목록에서 사라지는 데 새 분기가 필요하지 않다. 세션 자체는 남는다: 지운 것은
	 * 작품이지 그 사람이 플레이한 기록이 아니며, {@code resume} 은 계속 답한다.
	 *
	 * @param storyVersionIds 세션이 고정한 버전들 (I-4)
	 * @return 버전 id 로 찾는 지도. 사라졌거나 <b>지워진</b> 작품의 버전은 들어 있지 않다
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
						WHERE v.id IN (:ids) AND s.review_status <> 'deleted'
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
	 * <p><b>작성자 표시명은 카드와 같은 조회로 온다</b> (#258, R13.1). 커뮤니티 섹션이
	 * 작성자를 표기해야 하는데 카드마다 상세를 부르면 목록이 N+1 이 된다.
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
						rs.getString("author_display_name"), rs.getTimestamp("published_at").toInstant()))
				.list();

		boolean more = rows.size() > size;
		List<Row> page = more ? rows.subList(0, size) : rows;
		Map<UUID, List<String>> genresByStory = genresOf(page.stream().map(Row::id).toList());
		Instant newSince = this.clock.instant().minus(NEW_WINDOW);

		List<StoryCardView> cards = new ArrayList<>(page.size());
		for (Row row : page) {
			cards.add(new StoryCardView(row.id(), row.title(), row.coverUrl(),
					genresByStory.getOrDefault(row.id(), List.of()), row.shortDesc(),
					row.publishedAt().isAfter(newSince), row.authorType(), row.authorDisplayName()));
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
	 * <b>목록의 노출 조건이 여기 한 곳에 있다</b> (R2.3, I-8, §13-54).
	 *
	 * <p>공식 작품도 {@code approved} 를 요구한다 — 지금은 전부 승인 상태지만, 조건을 종류별로
	 * 다르게 두면 <b>공식이라는 이유로 검수를 건너뛰는 경로</b>가 생긴다.
	 *
	 * <p><b>목록은 {@code public} 만이다</b> (§13-54). {@code unlisted} 는 사람 검수를
	 * 요구하지 않는 범위이므로(R8.6), 목록에 올리면 <b>사람이 본 적 없는 작품을 둘러보던
	 * 사람이 그냥 만난다.</b> 목록이 인증 밖으로 나가면서(#306) 그 사람이 익명이 됐다.
	 * 상세는 {@code unlisted} 를 계속 연다 — 링크로 닿는 것이 그 상태의 정의다.
	 */
	private static String sql(LibrarySectionKey section, boolean afterCursor) {
		// 작성자 표시명은 JOIN 으로 함께 읽는다 (#258) — 카드마다 물으면 20장이 21번의 조회가
		// 된다. author_profile 은 같은 catalog 스키마이므로 JOIN 이 허용된다 (§5.3 은 스키마
		// '간' JOIN 을 금지한다).
		//
		// 조인 조건에 author_type 이 들어 있는 것이 의도다. 공식 작품의 author_ref 가 어떤
		// 경위로든 채워져 있어도 닉네임이 실리지 않는다 — DB 는 그 조합을 막지 않는다.
		String base = """
				SELECT s.id, s.title, s.cover_url, s.short_desc, s.author_type, s.published_at,
				       ap.display_name AS author_display_name
				FROM story s
				LEFT JOIN author_profile ap
				       ON ap.player_ref = s.author_ref AND s.author_type = 'user'
				WHERE s.review_status = 'approved'
				  AND s.visibility = 'public'
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
			String authorDisplayName, Instant publishedAt) {
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

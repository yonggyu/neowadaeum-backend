package com.neowadaeum.play.api;

import com.neowadaeum.catalog.query.GenreView;
import com.neowadaeum.catalog.query.LibrarySectionKey;
import com.neowadaeum.catalog.query.StoryBriefView;
import com.neowadaeum.catalog.query.StoryCatalogFacade;
import com.neowadaeum.catalog.query.StoryPage;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.common.spi.AiNotice;
import com.neowadaeum.common.spi.AiNoticeQuery;
import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.domain.SessionStatus;
import com.neowadaeum.play.repository.PlaySessionRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

/**
 * 라이브러리 조립 (§13.2, B-15).
 *
 * <p><b>여기가 두 스토어가 만나는 유일한 자리다</b> — 그리고 <b>애플리케이션 레벨에서만</b>
 * 만난다 (§5.3). SQL 은 각자의 스토어 안에서만 돈다.
 *
 * <p><b>조회 수를 세면서 만든다</b> (§15 — p95 300ms). 이어하기가 다섯 개여도 catalog 조회는
 * 두 번이고, 장르 섹션은 <b>실제로 쓰이는 장르만</b> 만든다.
 *
 * <p><b>고지 문구도 여기서 싣는다</b> (#257). 이 화면의 Footer 가 그것을 상시 표시하므로, 주지
 * 않으면 클라이언트가 {@code /landing} 을 한 번 더 부른다.
 */
@Service
public class LibraryService {

	/** 개요에 담는 섹션당 작품 수. 더 보기는 섹션 단위 조회가 맡는다 (§13.2). */
	private static final int OVERVIEW_PAGE = 10;

	/** 이어하기에 보여 줄 최대 개수. 전체 목록은 B-36 이다. */
	private static final Limit CONTINUE_LIMIT = Limit.of(10);

	private static final String RECOMMENDED_TITLE = "추천";

	private static final String COMMUNITY_TITLE = "사용자 작품";

	private static final Logger log = LoggerFactory.getLogger(LibraryService.class);

	private final StoryCatalogFacade stories;

	private final PlaySessionRepository sessions;

	private final AiNoticeQuery notices;

	public LibraryService(StoryCatalogFacade stories, PlaySessionRepository sessions,
			AiNoticeQuery notices) {
		this.stories = stories;
		this.sessions = sessions;
		this.notices = notices;
	}

	/**
	 * 화면 2.1 한 벌.
	 *
	 * @throws ApiException {@code INTERNAL_ERROR} — 고지 문구가 설정되지 않았다. 랜딩과 <b>같은
	 *     판단</b>이다 (R11.1, §11) — 빈 문자열로 흡수하면 고지가 없는 상태가 정상으로 보인다
	 */
	public LibraryView library(UUID playerRef) {
		AiNotice notice = this.notices.current().orElseThrow(() -> {
			log.error("ai.notice.missing surface=library — 고지 문구 없이 화면을 내보내지 않는다 (R11.1)");
			return new ApiException(ErrorCode.INTERNAL_ERROR);
		});

		List<GenreView> genres = this.stories.genres();
		Map<String, String> labels = genres.stream()
				.collect(java.util.stream.Collectors.toMap(GenreView::genreId, GenreView::label));

		List<LibraryView.SectionView> sections = new ArrayList<>();
		sections.add(section(new LibrarySectionKey(LibrarySectionKey.Kind.RECOMMENDED, null),
				RECOMMENDED_TITLE, null, OVERVIEW_PAGE));
		for (String genreKey : this.stories.officialGenreKeys()) {
			LibrarySectionKey key = new LibrarySectionKey(LibrarySectionKey.Kind.GENRE, genreKey);
			sections.add(section(key, labels.getOrDefault(genreKey, genreKey), null, OVERVIEW_PAGE));
		}
		sections.add(section(new LibrarySectionKey(LibrarySectionKey.Kind.COMMUNITY, null),
				COMMUNITY_TITLE, null, OVERVIEW_PAGE));

		return new LibraryView(genres, sections, continueSessions(playerRef), notice.text());
	}

	/**
	 * 섹션 단위 조회 (§13.2 — 재시도와 더 보기).
	 *
	 * @throws ApiException {@code NOT_FOUND} — 모르는 섹션 키. <b>빈 섹션으로 흡수하지 않는다</b>
	 */
	public LibraryView.SectionView section(String sectionKey, String cursor, Integer limit) {
		LibrarySectionKey key = LibrarySectionKey.parse(sectionKey)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		return section(key, titleOf(key), cursor, limit);
	}

	private String titleOf(LibrarySectionKey key) {
		return switch (key.kind()) {
			case RECOMMENDED -> RECOMMENDED_TITLE;
			case COMMUNITY -> COMMUNITY_TITLE;
			case GENRE -> this.stories.genres().stream()
					.filter(genre -> genre.genreId().equals(key.genreKey()))
					.map(GenreView::label)
					.findFirst()
					.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		};
	}

	private LibraryView.SectionView section(LibrarySectionKey key, String title, String cursor, Integer limit) {
		StoryPage page = this.stories.cards(key, cursor, limit);
		return new LibraryView.SectionView(key.value(), title, page.hasMore(), page.stories(),
				page.nextCursor());
	}

	/**
	 * 진행 중인 세션 (§13.2, R13.2).
	 *
	 * <p><b>자기 것만이다</b> — 조회가 {@code playerRef} 로만 이루어지므로 남의 세션이 섞일
	 * 경로가 없다 (I-3).
	 *
	 * <p>세션이 고정한 버전으로 작품 정보를 읽는다 (I-4). 새 버전이 발행돼도 이어하기 카드는
	 * <b>그 세션이 보던 챕터</b>를 가리킨다.
	 */
	private List<LibraryView.ContinueSessionView> continueSessions(UUID playerRef) {
		List<PlaySession> active = this.sessions
				.findByPlayerRefAndStatusAndDeletedAtIsNullOrderByUpdatedAtDesc(playerRef,
						SessionStatus.ACTIVE, CONTINUE_LIMIT);
		if (active.isEmpty()) {
			return List.of();
		}
		Map<UUID, StoryBriefView> briefs = this.stories
				.briefs(active.stream().map(PlaySession::getStoryVersionId).distinct().toList());

		List<LibraryView.ContinueSessionView> views = new ArrayList<>(active.size());
		for (PlaySession session : active) {
			StoryBriefView brief = briefs.get(session.getStoryVersionId());
			if (brief == null) {
				// 버전이 사라진 세션이다. 카드로 만들 수 없으므로 조용히 뺀다 — 화면 하나 때문에
				// 목록 전체를 실패시키지 않는다. 그 상태의 처리는 B-17 의 resume 이 정한다.
				continue;
			}
			views.add(new LibraryView.ContinueSessionView(session.getId(), brief.storyId(), brief.title(),
					brief.coverImage(), session.getChapterNo(),
					brief.chapterTitle(session.getChapterNo()).orElse(null), brief.totalChapters(),
					session.getLastSceneSummary(), session.getUpdatedAt()));
		}
		return views;
	}
}

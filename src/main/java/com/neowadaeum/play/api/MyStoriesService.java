package com.neowadaeum.play.api;

import com.neowadaeum.catalog.query.MyStoryPage;
import com.neowadaeum.catalog.query.MyStoryView;
import com.neowadaeum.catalog.query.StoryBriefView;
import com.neowadaeum.catalog.query.StoryCatalogFacade;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.domain.SessionStatus;
import com.neowadaeum.play.repository.PlaySessionRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

/**
 * 내 것들 (§13.7, 화면 2.4, B-36).
 *
 * <p><b>{@code in_progress} 는 존재하지 않는 상태였다</b> (§13-6). 원문의 쿼리 파라미터를 그대로
 * 받으면 저장된 값과 맞지 않아 <b>조회가 조용히 0건을 돌려준다</b> — 그래서 목록에 없는 값은
 * 400 으로 거절한다.
 */
@Service
public class MyStoriesService {

	private static final int DEFAULT_LIMIT = 20;

	private static final int MAX_LIMIT = 50;

	private final PlaySessionRepository sessions;

	private final StoryCatalogFacade stories;

	private final AiNoticeText notice;

	public MyStoriesService(PlaySessionRepository sessions, StoryCatalogFacade stories,
			AiNoticeText notice) {
		this.sessions = sessions;
		this.stories = stories;
		this.notice = notice;
	}

	/**
	 * 진행 중 / 완료 탭 (§13.7).
	 *
	 * @param status {@code active} 또는 {@code completed} (§13-6)
	 * @throws ApiException {@code VALIDATION_ERROR} — 그 밖의 값. <b>빈 목록으로 흡수하지 않는다</b>
	 */
	public MyStoriesView.Sessions sessions(UUID playerRef, String status, String cursor, Integer limit) {
		SessionStatus wanted = statusOf(status);
		int size = Math.clamp((limit != null) ? limit : DEFAULT_LIMIT, 1, MAX_LIMIT);
		Optional<Cursor> after = Cursor.parse(cursor);

		Limit window = Limit.of(size + 1);
		List<PlaySession> rows = after
				.map(from -> this.sessions.findMineAfter(playerRef, wanted, from.updatedAt(),
						from.sessionId(), window))
				.orElseGet(() -> this.sessions
						.findByPlayerRefAndStatusAndDeletedAtIsNullOrderByUpdatedAtDescIdDesc(playerRef,
								wanted, window));
		boolean more = rows.size() > size;
		List<PlaySession> page = more ? rows.subList(0, size) : rows;

		Map<UUID, StoryBriefView> briefs = this.stories
				.briefs(page.stream().map(PlaySession::getStoryVersionId).distinct().toList());

		List<MyStoriesView.SessionItem> items = new ArrayList<>(page.size());
		for (PlaySession session : page) {
			StoryBriefView brief = briefs.get(session.getStoryVersionId());
			if (brief == null) {
				// 버전이 사라진 세션이다. 한 줄 때문에 목록 전체를 실패시키지 않는다 (B-15 와 같은 판단).
				continue;
			}
			items.add(new MyStoriesView.SessionItem(session.getId(), brief.storyId(), brief.title(),
					brief.coverImage(), wanted.name().toLowerCase(Locale.ROOT), session.getChapterNo(),
					brief.totalChapters(), session.getUpdatedAt()));
		}
		return new MyStoriesView.Sessions(items,
				more ? new Cursor(page.getLast().getUpdatedAt(), page.getLast().getId()).encode() : null,
				more, this.notice.require("my_sessions"));
	}

	/**
	 * 내가 만든 작품 탭 (R13.4).
	 *
	 * <p>{@code playCount} 만 play 에서 온다 — 나머지는 catalog 의 것이다 (§5.3).
	 */
	public MyStoriesView.Stories stories(UUID playerRef, String cursor, Integer limit) {
		MyStoryPage page = this.stories.mine(playerRef, cursor, limit);

		List<MyStoriesView.StoryItem> items = new ArrayList<>(page.stories().size());
		for (MyStoryView story : page.stories()) {
			items.add(new MyStoriesView.StoryItem(story.storyId(), story.draftId(), story.title(),
					story.coverImage(), story.visibility(), story.reviewStatus(), story.rejectReasons(),
					this.sessions.countByStoryIdAndDeletedAtIsNull(story.storyId()), story.updatedAt(),
					story.submittedAt(), story.reviewedAt()));
		}
		return new MyStoriesView.Stories(items, page.nextCursor(), page.hasMore(),
				this.notice.require("my_stories"));
	}

	/**
	 * <b>§13-6 — 저장되는 상태는 넷이고 목록에 뜨는 것은 둘이다.</b>
	 *
	 * <p>{@code abandoned} · {@code expired} 는 사용자가 이어갈 수도 되돌아볼 수도 없다.
	 */
	private static SessionStatus statusOf(String status) {
		if ("active".equalsIgnoreCase(status)) {
			return SessionStatus.ACTIVE;
		}
		if ("completed".equalsIgnoreCase(status)) {
			return SessionStatus.COMPLETED;
		}
		throw new ApiException(ErrorCode.VALIDATION_ERROR);
	}

	/** 커서는 정렬 키 그대로다 — {@code (updatedAt, id)}. 같은 시각이 둘일 수 있다. */
	private record Cursor(Instant updatedAt, UUID sessionId) {

		String encode() {
			return Base64.getUrlEncoder().withoutPadding().encodeToString(
					"%d:%s".formatted(this.updatedAt.toEpochMilli(), this.sessionId)
							.getBytes(StandardCharsets.UTF_8));
		}

		static Optional<Cursor> parse(String raw) {
			if (raw == null || raw.isBlank()) {
				return Optional.empty();
			}
			try {
				String[] parts = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8)
						.split(":");
				return Optional.of(new Cursor(Instant.ofEpochMilli(Long.parseLong(parts[0])),
						UUID.fromString(parts[1])));
			}
			catch (RuntimeException ex) {
				// 해석되지 않는 커서는 처음부터로 본다 (§13-25 와 같은 판단).
				return Optional.empty();
			}
		}
	}
}

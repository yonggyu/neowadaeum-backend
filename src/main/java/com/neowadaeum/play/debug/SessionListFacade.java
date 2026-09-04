package com.neowadaeum.play.debug;

import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.repository.PlaySessionRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자가 세션을 <b>찾는</b> 길 (§14 Debug, #339).
 *
 * <p><b>{@link SessionDebugFacade} 와 일부러 다른 클래스다.</b> 둘의 차이는 무엇을 읽는지가
 * 아니라 <b>읽는 행위가 무엇을 남기는지</b>다 — 디버그는 원문을 열고 감사를 남기며(R12.3),
 * 이 목록은 원문을 열지 않으므로 남기지 않는다. 한 클래스에 두면 언젠가 한쪽 규칙이 다른
 * 쪽으로 새고, 새는 방향은 늘 <b>감사가 사라지는 쪽</b>이다.
 *
 * <p><b>작품 이름은 여기서 채우지 않는다.</b> catalog 는 다른 스토어이고 play 는 그 표를 알지
 * 못한다 (§5.3) — 조립은 부르는 쪽이 한다.
 */
@Service
public class SessionListFacade {

	private static final int DEFAULT_LIMIT = 20;

	private static final int MAX_LIMIT = 50;

	private final PlaySessionRepository sessions;

	public SessionListFacade(PlaySessionRepository sessions) {
		this.sessions = sessions;
	}

	/**
	 * 최근에 움직인 것부터.
	 *
	 * <p><b>커서 유무로 조회를 나눈다.</b> 한 쿼리에 {@code :cursorAt IS NULL} 을 넣으면
	 * PostgreSQL 이 null 파라미터의 타입을 정하지 못해 첫 쪽 요청이 전부 실패한다 — 같은
	 * 이유로 작품 필터도 나눈다.
	 *
	 * @param storyId 작품으로 좁힌다. {@code null} 이면 전체
	 */
	@Transactional(value = "playTransactionManager", readOnly = true)
	public SessionListPage list(UUID storyId, String cursor, Integer limit) {
		int size = Math.clamp((limit != null) ? limit : DEFAULT_LIMIT, 1, MAX_LIMIT);
		Optional<Cursor> after = Cursor.parse(cursor);
		Limit window = Limit.of(size + 1);

		List<PlaySession> rows = fetch(storyId, after, window);
		boolean more = rows.size() > size;
		List<PlaySession> page = more ? rows.subList(0, size) : rows;

		List<SessionListView> views = new ArrayList<>(page.size());
		for (PlaySession session : page) {
			views.add(viewOf(session));
		}
		return new SessionListPage(views,
				more ? new Cursor(page.getLast().getUpdatedAt(), page.getLast().getId()).encode() : null,
				more);
	}

	private List<PlaySession> fetch(UUID storyId, Optional<Cursor> after, Limit window) {
		if (storyId == null) {
			return after.map(from -> this.sessions.findAllAfter(from.updatedAt(), from.sessionId(), window))
					.orElseGet(() -> this.sessions.findAllByOrderByUpdatedAtDescIdDesc(window));
		}
		return after
				.map(from -> this.sessions.findByStoryAfter(storyId, from.updatedAt(), from.sessionId(),
						window))
				.orElseGet(() -> this.sessions.findByStoryIdOrderByUpdatedAtDescIdDesc(storyId, window));
	}

	/** 작품 이름은 비워 둔다 — 부르는 쪽이 채운다 (§5.3). */
	private static SessionListView viewOf(PlaySession session) {
		return new SessionListView(session.getId(), session.getStoryId(), session.getStoryVersionId(),
				null, session.getStatus().name().toLowerCase(Locale.ROOT), session.getTurnNo(),
				session.getChapterNo(), session.isTestSession(), session.getDeletedAt(),
				session.getCreatedAt(), session.getUpdatedAt());
	}

	/** 커서는 정렬 키 그대로다 — {@code (updatedAt, id)}. */
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

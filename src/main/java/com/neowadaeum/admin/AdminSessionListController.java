package com.neowadaeum.admin;

import com.neowadaeum.catalog.query.StoryBriefView;
import com.neowadaeum.catalog.query.StoryCatalogFacade;
import com.neowadaeum.common.web.PlayerRefResolver;
import com.neowadaeum.identity.access.AdminAccessGuard;
import com.neowadaeum.play.debug.SessionListFacade;
import com.neowadaeum.play.debug.SessionListPage;
import com.neowadaeum.play.debug.SessionListView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Debug 콘솔에 들어가는 문 (§14, #339).
 *
 * <p><b>{@link AdminDebugController} 와 다른 층이다.</b> 디버그는 <b>{@code sessionId} 를 이미
 * 아는 사람만</b> 부를 수 있었고, 세션을 <b>찾는</b> 경로가 없어 콘솔은 URL 로 직접 열어야
 * 들어갔다.
 *
 * <p><b>왜 두 층인가 — 감사가 무엇을 말해야 하는가</b> (R12.3, S-5). 디버그는 읽을 때마다
 * 감사 한 줄을 남긴다. 목록을 그리려고 후보 세션들의 원문을 미리 불러 두면 <b>열어 본 적 없는
 * 세션이 열람 기록에 남고</b>, 그 순간 감사 로그는 <i>"이 관리자가 이 세션을 봤다"</i> 를
 * 말하지 않게 된다. 그래서 이 경로는 <b>식별자와 메타데이터만</b> 주고 원문을 하나도 주지
 * 않으며, 열람 감사를 남기지 않는다.
 *
 * <p><b>행위는 남는다</b> (R14.5). "목록을 열었다"는 관리자 행위이고 {@code admin_audit_log} 의
 * 것이다 — 열람 감사와 다른 표, 다른 뜻이다.
 *
 * <p><b>조립만 한다.</b> 세션은 {@code play :: debug} 가, 작품 이름은 {@code catalog :: query}
 * 가 내준다 — 이 모듈은 어느 스토어에도 직접 닿지 않는다.
 */
@RestController
@RequestMapping("/api/v1/admin/sessions")
public class AdminSessionListController {

	private final SessionListFacade sessions;

	private final StoryCatalogFacade stories;

	private final AdminAccessGuard guard;

	private final PlayerRefResolver playerRefs;

	public AdminSessionListController(SessionListFacade sessions, StoryCatalogFacade stories,
			AdminAccessGuard guard, PlayerRefResolver playerRefs) {
		this.sessions = sessions;
		this.stories = stories;
		this.guard = guard;
		this.playerRefs = playerRefs;
	}

	/**
	 * 세션을 찾는다. 최근에 움직인 것부터.
	 *
	 * @param storyId 작품으로 좁힌다. 콘솔에서 가장 자주 하는 일이다 — <b>이 작품이 이상한
	 *                문장을 냈다</b>가 대개 출발점이기 때문이다
	 */
	@GetMapping
	public AdminSessionListResponse list(@RequestParam(required = false) UUID storyId,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit, HttpServletRequest request) {
		UUID adminUserId = this.guard.requireAdmin(this.playerRefs.currentPlayerRef(), request);

		SessionListPage page = this.sessions.list(storyId, cursor, limit);
		// 목록은 **특정 세션을 가리키지 않는다.** 자리를 채우려고 필터로 쓴 작품 id 를 넣으면
		// 감사 로그에 "session" 타입인데 작품 id 인 줄이 남아, 나중에 읽는 사람이 그것을
		// 세션 id 로 읽는다. 좁힌 축은 대상이 아니라 **이번 조회의 사실**이다.
		this.guard.recordAction(adminUserId, "admin.session.list", "session", null,
				details(storyId, page), request);
		return new AdminSessionListResponse(withStoryTitles(page.sessions()), page.nextCursor(),
				page.hasMore());
	}

	/** 이번 조회가 무엇을 물었고 몇 줄을 받았는가. <b>대상이 아니라 사실이다.</b> */
	private static Map<String, Object> details(UUID storyId, SessionListPage page) {
		Map<String, Object> details = new java.util.HashMap<>();
		details.put("count", page.sessions().size());
		if (storyId != null) {
			details.put("storyIdFilter", storyId.toString());
		}
		return details;
	}

	/**
	 * 작품 이름을 <b>쪽 단위로</b> 채운다 (§15).
	 *
	 * <p>줄마다 물으면 20줄짜리 목록이 21번의 조회가 된다. 세션이 고정한 버전으로 묻는 것은
	 * 그 지도가 이미 있기 때문이며(I-4), <b>지워진 작품의 버전은 지도에 없다</b> (§13-58) —
	 * 그 줄의 이름은 {@code null} 로 남는다. 세션은 지워지지 않았고 작품이 없어진 것이다.
	 */
	private List<SessionListView> withStoryTitles(List<SessionListView> rows) {
		Map<UUID, StoryBriefView> briefs = this.stories
				.briefs(rows.stream().map(SessionListView::storyVersionId).distinct().toList());

		List<SessionListView> named = new ArrayList<>(rows.size());
		for (SessionListView row : rows) {
			StoryBriefView brief = briefs.get(row.storyVersionId());
			named.add(new SessionListView(row.sessionId(), row.storyId(), row.storyVersionId(),
					(brief != null) ? brief.title() : null, row.status(), row.turnNo(),
					row.chapterNo(), row.testSession(), row.deletedAt(), row.createdAt(),
					row.updatedAt()));
		}
		return named;
	}
}

package com.neowadaeum.admin;

import com.neowadaeum.ai.debug.AiCallLogFacade;
import com.neowadaeum.common.web.PlayerRefResolver;
import com.neowadaeum.identity.access.AdminAccessGuard;
import com.neowadaeum.play.debug.SessionDebugFacade;
import com.neowadaeum.play.debug.SessionDebugView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin Debug 콘솔 (§14, B-41).
 *
 * <p>지금까지 <b>"모델이 이상한 이야기를 썼다"를 사후에 확인할 방법이 없었다.</b> 원문은
 * {@code ai_call_log} 에 쌓이지만 꺼내 볼 경로가 없었고, 이 경로가 그것을 연다.
 *
 * <p><b>조립만 한다.</b> 세션 쪽은 {@code play :: debug} 가, 원문 쪽은 {@code ai :: debug} 가
 * 각각 자기 데이터를 내준다 — 이 모듈은 어느 스토어에도 직접 닿지 않는다.
 *
 * <p><b>문 앞에 세 조건이 있다</b> (S-4). 역할·IP·2FA 승격을 모두 통과해야 하며, 그 판정은
 * {@code identity :: access} 가 한다.
 *
 * <p><b>원문을 읽은 사실은 파사드가 남긴다</b> (R12.3, S-5) — 여기서 남기면 <b>기록을 잊은
 * 호출자</b>가 생길 수 있다. 이 컨트롤러가 남기는 것은 <b>행위</b>다 (R14.5).
 */
@RestController
@RequestMapping("/api/v1/admin/sessions")
public class AdminDebugController {

	/** 화면 하나에 들어갈 만큼. 원문은 한 건이 길다 — 많이 주면 화면도 사람도 감당하지 못한다. */
	private static final int RECENT_CALLS = 5;

	private final SessionDebugFacade sessions;

	private final AiCallLogFacade calls;

	private final AdminAccessGuard guard;

	private final PlayerRefResolver playerRefs;

	public AdminDebugController(SessionDebugFacade sessions, AiCallLogFacade calls,
			AdminAccessGuard guard, PlayerRefResolver playerRefs) {
		this.sessions = sessions;
		this.calls = calls;
		this.guard = guard;
		this.playerRefs = playerRefs;
	}

	/**
	 * 한 세션에서 무슨 일이 일어났는지.
	 *
	 * <p><b>세션을 먼저 찾는다.</b> 없는 세션이면 원문을 읽을 일도 없고, 따라서 <b>있지도 않은
	 * 세션에 대한 열람 기록이 남지 않는다</b> — 순서를 뒤집으면 감사 로그가 존재하지 않는
	 * 세션으로 가득 찬다.
	 */
	@GetMapping("/{sessionId}/debug")
	public AdminSessionDebugResponse debug(@PathVariable UUID sessionId, HttpServletRequest request) {
		UUID adminUserId = this.guard.requireAdmin(this.playerRefs.currentPlayerRef(), request);

		SessionDebugView session = this.sessions.debug(sessionId);
		var raw = this.calls.recentCalls(sessionId, adminUserId, RECENT_CALLS);

		this.guard.recordAction(adminUserId, "admin.session.debug", "session", sessionId,
				Map.of("calls", raw.size()), request);
		return new AdminSessionDebugResponse(session, raw);
	}
}

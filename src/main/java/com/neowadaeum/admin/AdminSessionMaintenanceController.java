package com.neowadaeum.admin;

import com.neowadaeum.common.web.PlayerRefResolver;
import com.neowadaeum.identity.access.AdminAccessGuard;
import com.neowadaeum.play.debug.SessionRegenerateFacade;
import com.neowadaeum.play.debug.SessionRollbackFacade;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 되돌리기와 재생성 (§14, B-42).
 *
 * <p><b>보는 문과 고치는 문이 다르다.</b> Debug 콘솔(B-41)은 읽기만 하고, 상태를 바꾸는 것은
 * 여기다 — 한 자리에 두면 실수로 고치게 된다.
 *
 * <p><b>전건이 감사에 남는다</b> (R14.5). 되돌리기는 <b>무엇을 어디까지</b> 접었는지까지 남긴다 —
 * "되돌렸다"만으로는 사후에 무엇이 일어났는지 알 수 없다.
 */
@RestController
@RequestMapping("/api/v1/admin/sessions")
public class AdminSessionMaintenanceController {

	private final SessionRollbackFacade rollback;

	private final SessionRegenerateFacade regenerate;

	private final AdminAccessGuard guard;

	private final PlayerRefResolver playerRefs;

	public AdminSessionMaintenanceController(SessionRollbackFacade rollback,
			SessionRegenerateFacade regenerate, AdminAccessGuard guard, PlayerRefResolver playerRefs) {
		this.rollback = rollback;
		this.regenerate = regenerate;
		this.guard = guard;
		this.playerRefs = playerRefs;
	}

	/** {@code toTurnNo} 까지 되돌린다. 그 턴은 남는다 — 되돌린 뒤 화면에 떠 있을 턴이다. */
	@PostMapping("/{sessionId}/rollback")
	public SessionRollbackFacade.RollbackResult rollback(@PathVariable UUID sessionId,
			@Valid @RequestBody RollbackRequest body, HttpServletRequest request) {
		UUID adminUserId = this.guard.requireAdmin(this.playerRefs.currentPlayerRef(), request);

		SessionRollbackFacade.RollbackResult result = this.rollback.rollbackTo(sessionId,
				body.toTurnNo());
		this.guard.recordAction(adminUserId, "admin.session.rollback", "session", sessionId,
				Map.of("toTurnNo", result.turnNo(), "foldedTurns", result.foldedTurns(),
						"foldedSnapshots", result.foldedSnapshots(), "foldedSummaries",
						result.foldedSummaries()),
				request);
		return result;
	}

	/**
	 * 지목한 턴을 다시 만든다.
	 *
	 * <p><b>같은 선택으로 다시 만든다</b> — 재생성은 "그 선택에서 다른 본문"을 보는 것이지
	 * 다른 선택을 해 보는 것이 아니다.
	 */
	@PostMapping("/{sessionId}/turns/{turnNo}/regenerate")
	public SessionRegenerateFacade.RegenerateResult regenerate(@PathVariable UUID sessionId,
			@PathVariable int turnNo, HttpServletRequest request) {
		UUID adminUserId = this.guard.requireAdmin(this.playerRefs.currentPlayerRef(), request);

		SessionRegenerateFacade.RegenerateResult result = this.regenerate.regenerate(sessionId, turnNo);
		this.guard.recordAction(adminUserId, "admin.session.regenerate", "session", sessionId,
				Map.of("turnNo", turnNo, "blocked", result.blocked()), request);
		return result;
	}
}

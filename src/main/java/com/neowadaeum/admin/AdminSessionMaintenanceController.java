package com.neowadaeum.admin;

import com.neowadaeum.common.web.PlayerRefResolver;
import com.neowadaeum.identity.access.AdminAccessGuard;
import com.neowadaeum.play.debug.AdminFreeInputFacade;
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

	private final AdminFreeInputFacade freeInput;

	private final AdminAccessGuard guard;

	private final PlayerRefResolver playerRefs;

	public AdminSessionMaintenanceController(SessionRollbackFacade rollback,
			SessionRegenerateFacade regenerate, AdminFreeInputFacade freeInput, AdminAccessGuard guard,
			PlayerRefResolver playerRefs) {
		this.rollback = rollback;
		this.regenerate = regenerate;
		this.freeInput = freeInput;
		this.guard = guard;
		this.playerRefs = playerRefs;
	}

	/**
	 * 임의의 행동 문장으로 턴을 만든다 (R14.1~R14.3).
	 *
	 * <p><b>테스트 세션에서만 열린다</b> (I-18) — 남의 이야기에 관리자가 문장을 넣는 것은
	 * 디버그가 아니라 개입이다. 그리고 <b>L1 을 지난다</b> (I-17).
	 *
	 * <p><b>행동 문장을 감사에 싣지 않는다</b> (S-3, S-11). 남는 것은 "언제 누가 자유입력을
	 * 썼는가"이며, 무엇을 넣었는지는 원문 보관처(`ai_call_log`)에서 디버그로 본다.
	 */
	@PostMapping("/{sessionId}/turns/free")
	public AdminFreeInputFacade.FreeInputResult free(@PathVariable UUID sessionId,
			@Valid @RequestBody FreeInputRequest body, HttpServletRequest request) {
		UUID adminUserId = this.guard.requireAdmin(this.playerRefs.currentPlayerRef(), request);

		AdminFreeInputFacade.FreeInputResult result = this.freeInput.submit(sessionId, body.action());
		this.guard.recordAction(adminUserId, "admin.session.freeInput", "session", sessionId,
				Map.of("turnNo", result.turnNo(), "blocked", result.blocked()), request);
		return result;
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

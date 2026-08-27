package com.neowadaeum.identity.api;

import com.neowadaeum.common.web.PlayerRefResolver;
import com.neowadaeum.identity.auth.AdminAccessGuard;
import com.neowadaeum.identity.auth.AdminTotpService;
import com.neowadaeum.identity.auth.AuthTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 2FA API (B-40, R14.6, S-4).
 *
 * <p><b>세 경로의 문턱이 서로 다르다.</b> 등록은 승격 <b>이전</b>이어야 하고(그러지 않으면 아직
 * 등록하지 않은 관리자가 등록할 방법이 없다), 재등록은 승격 <b>이후</b>여야 하며, 검증은 승격을
 * <b>만들어 내는</b> 자리다.
 *
 * <p><b>전건이 감사에 남는다</b> (R14.5). 등록·확정·검증은 모두 관리자 행위다.
 */
@RestController
@RequestMapping("/api/v1/admin/2fa")
public class AdminTotpController {

	/** 인증기 화면에 보일 이름. <b>회원을 특정하지 않는다</b> — 이메일을 넣지 않는다 (I-3). */
	private static final String ACCOUNT_LABEL = "admin";

	private final AdminTotpService totp;

	private final AdminAccessGuard guard;

	private final AuthTokenService tokens;

	private final PlayerRefResolver playerRefs;

	public AdminTotpController(AdminTotpService totp, AdminAccessGuard guard, AuthTokenService tokens,
			PlayerRefResolver playerRefs) {
		this.totp = totp;
		this.guard = guard;
		this.tokens = tokens;
		this.playerRefs = playerRefs;
	}

	/**
	 * 등록을 시작한다. <b>비밀이 나가는 유일한 응답이다.</b>
	 *
	 * <p>이미 확정된 등록이 있으면 승격을 함께 요구한다 — 재등록은 두 번째 요소를 갈아 치우는
	 * 행위이므로, 그 자체가 두 번째 요소 뒤에 있어야 한다.
	 */
	@PostMapping("/enroll")
	public TotpEnrollmentResponse enroll(HttpServletRequest request) {
		UUID playerRef = this.playerRefs.currentPlayerRef();
		UUID adminUserId = this.guard.requireRoleAndNetwork(playerRef, request);
		if (this.totp.isEnrolled(adminUserId)) {
			this.guard.requireStepUp(playerRef, adminUserId, request);
		}

		AdminTotpService.Enrollment enrollment = this.totp.beginEnrollment(adminUserId, ACCOUNT_LABEL);
		// 비밀도 URI 도 감사에 싣지 않는다 (S-3). 남길 것은 "언제 누가 등록을 시작했나" 다.
		this.guard.recordAction(adminUserId, "admin.2fa.enroll", "admin", adminUserId, Map.of(), request);
		return new TotpEnrollmentResponse(enrollment.secret(), enrollment.otpauthUri());
	}

	/** 등록을 확정한다. 이 코드가 맞아야 비로소 이 비밀이 문을 연다. */
	@PostMapping("/confirm")
	public TotpStepUpResponse confirm(@Valid @RequestBody TotpCodeRequest body, HttpServletRequest request) {
		UUID playerRef = this.playerRefs.currentPlayerRef();
		UUID adminUserId = this.guard.requireRoleAndNetwork(playerRef, request);

		this.totp.confirmEnrollment(adminUserId, body.code());
		this.guard.recordAction(adminUserId, "admin.2fa.confirm", "admin", adminUserId, Map.of(), request);
		return stepUpFor(playerRef);
	}

	/**
	 * 코드를 검증하고 <b>짧은 수명의 승격</b>을 발급한다.
	 *
	 * <p>이 경로만이 승격을 만든다. 관리자 경로는 그 값을 {@code X-Admin-Step-Up} 으로 함께 보낸다.
	 */
	@PostMapping("/verify")
	public TotpStepUpResponse verify(@Valid @RequestBody TotpCodeRequest body, HttpServletRequest request) {
		UUID playerRef = this.playerRefs.currentPlayerRef();
		UUID adminUserId = this.guard.requireRoleAndNetwork(playerRef, request);

		this.totp.verify(adminUserId, body.code());
		this.guard.recordAction(adminUserId, "admin.2fa.verify", "admin", adminUserId, Map.of(), request);
		return stepUpFor(playerRef);
	}

	/** 토큰의 {@code sub} 는 언제나 {@code playerRef} 다 (I-3) — {@code user.id} 는 나가지 않는다. */
	private TotpStepUpResponse stepUpFor(UUID playerRef) {
		AuthTokenService.AdminStepUp stepUp = this.tokens.issueAdminStepUp(playerRef);
		return new TotpStepUpResponse(stepUp.token(), stepUp.expiresIn());
	}
}

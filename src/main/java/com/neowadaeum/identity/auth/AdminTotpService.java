package com.neowadaeum.identity.auth;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.identity.domain.AdminTotp;
import com.neowadaeum.identity.repository.AdminTotpRepository;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 2FA 의 등록·확정·검증 (B-40, R14.6, S-4).
 *
 * <p><b>확인되기 전의 비밀은 문을 열지 못한다.</b> 등록은 두 걸음이다 — 한 걸음으로 줄이면 QR 을
 * 잘못 찍은 관리자가 그 사실을 다음 로그인에 가서야 알고, 그때는 이미 잠긴 뒤다.
 *
 * <p><b>같은 코드가 두 번 통하지 않는다.</b> 코드는 30초 동안 같으므로, 통과한 스텝을 남기고
 * 그보다 뒤의 스텝만 받는다.
 *
 * <p><b>비밀도 코드도 예외 메시지에 싣지 않는다</b> (S-3). 실패는 전부 같은 오류다 — 어느
 * 단계에서 어긋났는지 알리면 그것이 단서가 된다 (S-6).
 */
@Service
public class AdminTotpService {

	/** RFC 4226 권장 길이. 160비트는 HMAC-SHA1 의 블록과 맞고, 짧게 잡을 이유가 없다. */
	private static final int SECRET_BYTES = 20;

	/**
	 * 검증할 때 함께 보는 앞뒤 스텝 수.
	 *
	 * <p><b>1 이다.</b> 인증기의 시계는 서버와 몇 초씩 어긋나고, 코드를 다 입력하는 사이에 창이
	 * 넘어간다 — 0 이면 정상 사용이 자주 실패한다. 반대로 넓힐수록 <b>동시에 유효한 코드가
	 * 늘어난다</b>: 추측 성공 확률이 그만큼 곱해진다.
	 */
	private static final int WINDOW_STEPS = 1;

	private final AdminTotpRepository registrations;

	private final AdminTotpCipher cipher;

	private final Clock clock;

	private final SecureRandom random = new SecureRandom();

	public AdminTotpService(AdminTotpRepository registrations, AdminTotpCipher cipher, Clock clock) {
		this.registrations = registrations;
		this.cipher = cipher;
		this.clock = clock;
	}

	/**
	 * 새 비밀을 만들어 등록을 시작한다.
	 *
	 * <p><b>비밀을 돌려주는 유일한 자리다.</b> 이후로는 어떤 경로로도 읽히지 않는다.
	 *
	 * <p>이미 확정된 등록이 있어도 갈아 끼운다. <b>그 경우 호출자가 단계 승격을 요구해야 한다</b> —
	 * 재등록은 2FA 를 갈아 치우는 행위이므로, 그 자체가 2FA 뒤에 있어야 한다.
	 */
	@Transactional("identityTransactionManager")
	public Enrollment beginEnrollment(UUID adminUserId, String accountLabel) {
		byte[] secret = new byte[SECRET_BYTES];
		this.random.nextBytes(secret);
		String wrapped = this.cipher.wrap(secret);
		Instant now = this.clock.instant();

		this.registrations.findById(adminUserId)
				.ifPresentOrElse(existing -> existing.replaceSecret(wrapped, now),
						() -> this.registrations.save(AdminTotp.enroll(adminUserId, wrapped, now)));

		return new Enrollment(Base32.encode(secret), otpauthUri(Base32.encode(secret), accountLabel));
	}

	/** 등록을 확정한다. 이 코드가 맞아야 <b>비로소</b> 이 비밀이 문을 연다. */
	@Transactional("identityTransactionManager")
	public void confirmEnrollment(UUID adminUserId, String code) {
		AdminTotp registration = this.registrations.findById(adminUserId)
				.orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
		registration.confirm(requireMatchingStep(registration, code), this.clock.instant());
	}

	/**
	 * 코드를 검증한다. <b>확정된 등록만 통과한다.</b>
	 *
	 * @throws ApiException {@code FORBIDDEN} — 등록이 없거나, 확정 전이거나, 코드가 틀렸거나,
	 *     이미 쓴 코드다. <b>넷을 구분해 알리지 않는다</b> (S-6)
	 */
	@Transactional("identityTransactionManager")
	public void verify(UUID adminUserId, String code) {
		AdminTotp registration = this.registrations.findById(adminUserId)
				.orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
		if (!registration.isConfirmed()) {
			throw new ApiException(ErrorCode.FORBIDDEN);
		}
		registration.markUsed(requireMatchingStep(registration, code));
	}

	/** 확정된 등록을 가지고 있는가. 재등록에 승격을 요구할지 판단하는 데 쓴다. */
	@Transactional(value = "identityTransactionManager", readOnly = true)
	public boolean isEnrolled(UUID adminUserId) {
		return this.registrations.findById(adminUserId).map(AdminTotp::isConfirmed).orElse(false);
	}

	/**
	 * 코드가 가리키는 스텝. 맞는 스텝이 없으면 통과하지 못한다.
	 *
	 * <p><b>이미 쓴 스텝은 맞아도 거절한다.</b> 재사용 방지가 없으면 창 안에서 같은 여섯 자리가
	 * 몇 번이든 통한다.
	 */
	private long requireMatchingStep(AdminTotp registration, String code) {
		byte[] secret = this.cipher.unwrap(registration.getSecretEnc());
		long current = TotpCodes.stepAt(this.clock.instant());
		for (long step = current - WINDOW_STEPS; step <= current + WINDOW_STEPS; step++) {
			if (TotpCodes.matches(secret, code, step) && !registration.hasUsed(step)) {
				return step;
			}
		}
		throw new ApiException(ErrorCode.FORBIDDEN);
	}

	/**
	 * 인증기 앱이 읽는 표기.
	 *
	 * <p>라벨에 <b>이메일을 쓰지 않는다</b> (I-3, §12) — 호출자가 넘기는 것은 회원을 특정하지 않는
	 * 문자열이며, 화면에 무엇으로 보일지를 정할 뿐이다.
	 */
	private String otpauthUri(String base32Secret, String accountLabel) {
		String issuer = URLEncoder.encode(AuthTokenService.ISSUER, StandardCharsets.UTF_8);
		return "otpauth://totp/%s:%s?secret=%s&issuer=%s&digits=6&period=%d".formatted(issuer,
				URLEncoder.encode(accountLabel, StandardCharsets.UTF_8), base32Secret, issuer,
				TotpCodes.STEP.toSeconds());
	}

	/**
	 * 등록을 시작하며 한 번만 돌려주는 값.
	 *
	 * @param secret 인증기에 직접 입력할 표기
	 * @param otpauthUri QR 로 만들 표기. 같은 비밀을 담고 있다
	 */
	public record Enrollment(String secret, String otpauthUri) {
	}
}

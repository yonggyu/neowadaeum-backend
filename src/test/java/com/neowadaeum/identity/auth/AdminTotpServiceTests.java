package com.neowadaeum.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.identity.domain.AdminTotp;
import com.neowadaeum.identity.repository.AdminTotpRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * B-40 — 등록 두 걸음과 재사용 방지 (R14.6, S-4).
 *
 * <p>저장소는 메모리에 둔다. <b>여기서 확인할 것은 영속이 아니라 판단</b>이다 — 확인 전 비밀이
 * 문을 여는가, 같은 코드가 두 번 통하는가, 시계가 조금 어긋나도 통하는가.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class AdminTotpServiceTests {

	private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

	private final Map<UUID, AdminTotp> stored = new HashMap<>();

	private final AdminTotpRepository registrations = mock(AdminTotpRepository.class);

	private final UUID adminUserId = UUID.randomUUID();

	@BeforeEach
	void wireInMemoryStore() {
		given(this.registrations.findById(any()))
				.willAnswer(call -> Optional.ofNullable(this.stored.get(call.getArgument(0))));
		willAnswer(call -> {
			AdminTotp saved = call.getArgument(0);
			this.stored.put(saved.getUserId(), saved);
			return saved;
		}).given(this.registrations).save(any());
	}

	/** 등록만 해서는 통과하지 못한다 — <b>확인이 두 번째 걸음이다.</b> */
	@Test
	void R14_6_an_unconfirmed_secret_does_not_open_the_door() {
		AdminTotpService service = serviceAt(NOW);
		String code = codeFor(service.beginEnrollment(this.adminUserId, "admin"), NOW);

		assertThatThrownBy(() -> service.verify(this.adminUserId, code))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.FORBIDDEN);
	}

	/** 확인을 마치면 통과한다. */
	@Test
	void R14_6_a_confirmed_secret_verifies() {
		AdminTotpService service = serviceAt(NOW);
		AdminTotpService.Enrollment enrollment = service.beginEnrollment(this.adminUserId, "admin");
		service.confirmEnrollment(this.adminUserId, codeFor(enrollment, NOW));

		Instant later = NOW.plus(TotpCodes.STEP);
		serviceAt(later).verify(this.adminUserId, codeFor(enrollment, later));
	}

	/**
	 * <b>같은 코드가 두 번 통하지 않는다.</b>
	 *
	 * <p>코드는 30초 동안 같다. 재사용 방지가 없으면 어깨너머로 본 여섯 자리가 그 창 안에서
	 * 몇 번이든 쓰인다.
	 */
	@Test
	void R14_6_the_same_code_never_passes_twice() {
		AdminTotpService service = serviceAt(NOW);
		AdminTotpService.Enrollment enrollment = service.beginEnrollment(this.adminUserId, "admin");
		service.confirmEnrollment(this.adminUserId, codeFor(enrollment, NOW));

		Instant later = NOW.plus(TotpCodes.STEP);
		String code = codeFor(enrollment, later);
		AdminTotpService atLater = serviceAt(later);
		atLater.verify(this.adminUserId, code);

		assertThatThrownBy(() -> atLater.verify(this.adminUserId, code))
				.isInstanceOf(ApiException.class);
	}

	/** 확정에 쓴 코드도 다시 쓰이지 않는다 — 확정과 검증이 같은 창을 공유한다. */
	@Test
	void R14_6_the_confirming_code_cannot_be_replayed() {
		AdminTotpService service = serviceAt(NOW);
		AdminTotpService.Enrollment enrollment = service.beginEnrollment(this.adminUserId, "admin");
		String code = codeFor(enrollment, NOW);
		service.confirmEnrollment(this.adminUserId, code);

		assertThatThrownBy(() -> service.verify(this.adminUserId, code))
				.isInstanceOf(ApiException.class);
	}

	/**
	 * <b>앞 스텝의 코드도 받는다.</b> 인증기의 시계는 서버와 몇 초씩 어긋나고, 코드를 다 입력하는
	 * 사이에 창이 넘어간다 — 받지 않으면 정상 사용이 자주 실패한다.
	 */
	@Test
	void R14_6_a_code_from_the_previous_step_still_passes() {
		AdminTotpService service = serviceAt(NOW);
		AdminTotpService.Enrollment enrollment = service.beginEnrollment(this.adminUserId, "admin");
		service.confirmEnrollment(this.adminUserId, codeFor(enrollment, NOW.minus(TotpCodes.STEP)));

		assertThat(this.stored.get(this.adminUserId).isConfirmed()).isTrue();
	}

	/** 창 밖의 코드는 받지 않는다 — 넓힐수록 동시에 유효한 코드가 늘어난다. */
	@Test
	void R14_6_a_code_outside_the_window_is_rejected() {
		AdminTotpService service = serviceAt(NOW);
		AdminTotpService.Enrollment enrollment = service.beginEnrollment(this.adminUserId, "admin");
		String stale = codeFor(enrollment, NOW.minus(TotpCodes.STEP.multipliedBy(5)));

		assertThatThrownBy(() -> service.confirmEnrollment(this.adminUserId, stale))
				.isInstanceOf(ApiException.class);
	}

	/** 등록이 없으면 통과할 것이 없다. 코드 불일치와 <b>구분해 알리지 않는다</b> (S-6). */
	@Test
	void S6_an_unknown_registration_fails_the_same_way() {
		AdminTotpService service = serviceAt(NOW);

		assertThatThrownBy(() -> service.verify(this.adminUserId, "000000"))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.FORBIDDEN);
	}

	/** <b>재등록은 확인과 재사용 기록을 함께 되돌린다.</b> 옛 확인이 남으면 새 비밀이 곧 유효해진다. */
	@Test
	void R14_6_re_enrolling_invalidates_the_previous_secret() {
		AdminTotpService service = serviceAt(NOW);
		AdminTotpService.Enrollment first = service.beginEnrollment(this.adminUserId, "admin");
		service.confirmEnrollment(this.adminUserId, codeFor(first, NOW));
		assertThat(service.isEnrolled(this.adminUserId)).isTrue();

		service.beginEnrollment(this.adminUserId, "admin");

		assertThat(service.isEnrolled(this.adminUserId)).isFalse();
		Instant later = NOW.plus(TotpCodes.STEP);
		assertThatThrownBy(() -> serviceAt(later).verify(this.adminUserId, codeFor(first, later)))
				.isInstanceOf(ApiException.class);
	}

	/** <b>비밀이 예외 메시지에 실리지 않는다</b> (S-3). */
	@Test
	void S3_the_secret_never_appears_in_a_failure_message() {
		AdminTotpService service = serviceAt(NOW);
		AdminTotpService.Enrollment enrollment = service.beginEnrollment(this.adminUserId, "admin");

		assertThatThrownBy(() -> service.verify(this.adminUserId, "000000"))
				.hasMessageNotContaining(enrollment.secret());
	}

	/** 인증기 앱이 읽는 표기에 회원을 특정하는 것이 들어가지 않는다 (I-3). */
	@Test
	void I3_the_otpauth_uri_carries_no_member_identity() {
		AdminTotpService.Enrollment enrollment = serviceAt(NOW)
				.beginEnrollment(this.adminUserId, "admin");

		assertThat(enrollment.otpauthUri())
				.startsWith("otpauth://totp/")
				.contains(enrollment.secret())
				.doesNotContain(this.adminUserId.toString());
	}

	private AdminTotpService serviceAt(Instant instant) {
		return new AdminTotpService(this.registrations, cipher(),
				Clock.fixed(instant, ZoneOffset.UTC));
	}

	/** 인증기가 그 시각에 보여 줄 코드. 테스트는 <b>앱의 자리에 선다.</b> */
	private String codeFor(AdminTotpService.Enrollment enrollment, Instant instant) {
		return TotpCodes.codeAt(decodeBase32(enrollment.secret()), TotpCodes.stepAt(instant));
	}

	/** 테스트 키다. 어떤 실제 비밀도 감싸지 않는다 (S-11). */
	private AdminTotpCipher cipher() {
		byte[] material = new byte[32];
		java.util.Arrays.fill(material, (byte) 3);
		return new AdminTotpCipher(new AdminTotpProperties(Base64.getEncoder().encodeToString(material)));
	}

	/** {@link Base32} 는 인코딩만 한다 — 되돌리는 쪽은 인증기의 몫이므로 여기서 흉내 낸다. */
	private static byte[] decodeBase32(String encoded) {
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		int buffer = 0;
		int bits = 0;
		for (char c : encoded.toCharArray()) {
			buffer = (buffer << 5) | "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c);
			bits += 5;
			if (bits >= Byte.SIZE) {
				bits -= Byte.SIZE;
				out.write((buffer >>> bits) & 0xFF);
			}
		}
		return out.toByteArray();
	}

}

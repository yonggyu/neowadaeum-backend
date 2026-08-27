package com.neowadaeum.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * <b>§10.1-13 필수 테스트 — 생일 경계값</b> (R10.2, B-13).
 *
 * <p>경계가 하루 어긋나면 그날 생일인 사람이 전부 거부되거나, 하루 이르게 통과한다.
 * 어느 쪽도 <b>운영에서 발견되는 종류</b>이고 그때는 이미 계정이 만들어진 뒤다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class AgeGateTests {

	/** KST 로 2026-08-27 정오. 판정이 KST 라는 것을 이 값이 전제한다. */
	private static final Instant NOON_KST = Instant.parse("2026-08-27T03:00:00Z");

	private final AgeGate gate = new AgeGate(Clock.fixed(NOON_KST, ZoneOffset.UTC));

	/** <b>만 15세 되기 하루 전은 거부된다.</b> */
	@Test
	void R10_2_the_day_before_the_fifteenth_birthday_is_rejected() {
		LocalDate dayBefore = LocalDate.of(2011, 8, 28);

		assertThat(this.gate.isEligible(dayBefore)).isFalse();
		assertThatThrownBy(() -> this.gate.requireEligible(dayBefore))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.AGE_RESTRICTED);
	}

	/** <b>생일 당일은 통과한다.</b> 만 나이는 생일에 오른다 — 그날 이미 15세다. */
	@Test
	void R10_2_the_fifteenth_birthday_itself_passes() {
		assertThat(this.gate.isEligible(LocalDate.of(2011, 8, 27))).isTrue();
	}

	/** 하루 지난 사람도 당연히 통과한다. 경계가 한쪽으로만 맞는 구현을 거른다. */
	@Test
	void R10_2_the_day_after_passes_too() {
		assertThat(this.gate.isEligible(LocalDate.of(2011, 8, 26))).isTrue();
	}

	/**
	 * <b>KST 로 판정한다.</b>
	 *
	 * <p>UTC 로 재면 한국 시각 자정~오전 9시 사이에 <b>생일이 하루 늦게 온다.</b> 그 시간대에
	 * 가입하는 사람은 자기 생일에 거부된다.
	 */
	@Test
	void S13_24_the_boundary_follows_kst_not_the_server_zone() {
		// KST 2026-08-27 00:30 = UTC 2026-08-26 15:30. UTC 로 재면 아직 26일이다.
		AgeGate justAfterMidnightKst = new AgeGate(
				Clock.fixed(Instant.parse("2026-08-26T15:30:00Z"), ZoneOffset.UTC));

		assertThat(justAfterMidnightKst.isEligible(LocalDate.of(2011, 8, 27)))
				.as("KST 로는 이미 생일이다")
				.isTrue();
	}

	/** 윤일 생일도 경계가 성립한다 — {@code plusYears} 가 2월 28일로 맞춰 준다. */
	@Test
	void R10_2_a_leap_day_birthday_has_a_boundary_too() {
		AgeGate onFeb28 = new AgeGate(Clock.fixed(Instant.parse("2027-02-27T15:00:00Z"), ZoneOffset.UTC));

		assertThat(onFeb28.isEligible(LocalDate.of(2012, 2, 29))).isTrue();
		assertThat(onFeb28.isEligible(LocalDate.of(2012, 3, 1))).isFalse();
	}
}

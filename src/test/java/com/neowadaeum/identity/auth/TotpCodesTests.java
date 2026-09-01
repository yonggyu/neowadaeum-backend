package com.neowadaeum.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * TOTP 코드 계산 (RFC 6238).
 *
 * <p><b>규격의 시험 벡터로 고정한다.</b> 우리 구현이 우리 구현과 일치하는지를 확인하면 아무것도
 * 확인하지 못한다 — 맞춰야 할 상대는 <b>사용자 손에 있는 인증기 앱</b>이고, 그 앱이 따르는 것이
 * 이 규격이다.
 */
class TotpCodesTests {

	/** RFC 6238 부록 B 의 SHA-1 비밀. 규격이 공개한 값이며 어떤 실제 비밀도 아니다. */
	private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

	/** RFC 6238 부록 B — 시각과 기대 코드가 규격에 적혀 있다. */
	@Test
	void R14_6_matches_the_specification_test_vectors() {
		assertThat(TotpCodes.codeAt(RFC_SECRET, TotpCodes.stepAt(Instant.ofEpochSecond(59L))))
				.isEqualTo("287082");
		assertThat(TotpCodes.codeAt(RFC_SECRET, TotpCodes.stepAt(Instant.ofEpochSecond(1111111109L))))
				.isEqualTo("081804");
		assertThat(TotpCodes.codeAt(RFC_SECRET, TotpCodes.stepAt(Instant.ofEpochSecond(1234567890L))))
				.isEqualTo("005924");
		assertThat(TotpCodes.codeAt(RFC_SECRET, TotpCodes.stepAt(Instant.ofEpochSecond(2000000000L))))
				.isEqualTo("279037");
	}

	/** <b>앞자리 0 이 사라지지 않는다.</b> 다섯 자리로 나오면 사용자가 보는 것과 달라진다. */
	@Test
	void R14_6_a_code_keeps_its_leading_zero() {
		assertThat(TotpCodes.codeAt(RFC_SECRET, TotpCodes.stepAt(Instant.ofEpochSecond(1234567890L))))
				.hasSize(6)
				.startsWith("0");
	}

	/** 스텝은 30초 폭이다. 창 안에서는 같은 코드이고, 넘어가면 달라진다. */
	@Test
	void R14_6_a_step_covers_thirty_seconds() {
		long start = TotpCodes.stepAt(Instant.ofEpochSecond(60L));

		assertThat(TotpCodes.stepAt(Instant.ofEpochSecond(89L))).isEqualTo(start);
		assertThat(TotpCodes.stepAt(Instant.ofEpochSecond(90L))).isEqualTo(start + 1);
	}

	/** 비밀이 다르면 코드가 다르다 — 그러지 않으면 비밀은 아무 역할도 하지 않는다. */
	@Test
	void R14_6_a_different_secret_yields_a_different_code() {
		byte[] other = "09876543210987654321".getBytes(StandardCharsets.US_ASCII);

		assertThat(TotpCodes.codeAt(other, 1L)).isNotEqualTo(TotpCodes.codeAt(RFC_SECRET, 1L));
	}

	@Test
	void R14_6_matches_accepts_the_code_of_that_step_only() {
		long step = TotpCodes.stepAt(Instant.ofEpochSecond(59L));

		assertThat(TotpCodes.matches(RFC_SECRET, "287082", step)).isTrue();
		assertThat(TotpCodes.matches(RFC_SECRET, "287082", step + 1)).isFalse();
	}

	/** 사용자가 붙여 넣은 공백은 코드가 아니다. 다만 <b>내용</b>이 다르면 통과하지 못한다. */
	@Test
	void R14_6_matches_trims_but_does_not_forgive() {
		long step = TotpCodes.stepAt(Instant.ofEpochSecond(59L));

		assertThat(TotpCodes.matches(RFC_SECRET, " 287082 ", step)).isTrue();
		assertThat(TotpCodes.matches(RFC_SECRET, "28708", step)).isFalse();
		assertThat(TotpCodes.matches(RFC_SECRET, null, step)).isFalse();
	}
}

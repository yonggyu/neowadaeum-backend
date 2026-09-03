package com.neowadaeum.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * #287 — 공개 표시명의 규칙 (§13-7).
 *
 * <p><b>이 값은 사용자가 정하고 다른 사용자에게 보인다.</b> 그래서 여기서 확인하는 것은 화면이
 * 예뻐지는가가 아니라 <b>같아 보이는 이름</b>과 <b>남을 사칭하는 이름</b>이 만들어지지 않는가다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class DisplayNamesTests {

	@Test
	void S13_7_a_plain_name_passes_unchanged() {
		assertThat(DisplayNames.normalize("연우")).isEqualTo("연우");
	}

	// ── @ 는 값에 없다 ────────────────────────────────────────

	/**
	 * <b>벗기지 않고 거절한다.</b> 조용히 벗기면 사용자가 무엇을 저장했는지 모르게 되고, 표시명의
	 * 정본이 서버와 화면 두 곳에 생긴다.
	 */
	@Test
	void S287_a_leading_at_sign_is_rejected_not_stripped() {
		assertThatThrownBy(() -> DisplayNames.normalize("@연우"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("@");
	}

	/** 앞이 아닌 자리의 {@code @} 도 허용목록에 없다. */
	@Test
	void S287_an_at_sign_anywhere_is_not_allowed() {
		assertThatThrownBy(() -> DisplayNames.normalize("연우@밤"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	// ── 정규화 ───────────────────────────────────────────────

	/** 양끝 공백을 허용하면 {@code " 연우"} 와 {@code "연우"} 가 다른 행이면서 같게 보인다. */
	@Test
	void S287_surrounding_whitespace_is_removed() {
		assertThat(DisplayNames.normalize("  연우  ")).isEqualTo("연우");
	}

	/** 내부 연속 공백도 같은 이유로 하나로 모인다. */
	@Test
	void S287_repeated_inner_whitespace_collapses() {
		assertThat(DisplayNames.normalize("달빛   서점")).isEqualTo("달빛 서점");
	}

	/**
	 * <b>NFC 로 모은다.</b> 자모로 분해돼 들어온 한글을 완성형으로 저장하지 않으면 같은 이름이
	 * 두 표현으로 남아 조회와 중복 판정이 어긋난다.
	 */
	@Test
	void S287_decomposed_hangul_is_composed_before_storing() {
		String decomposed = "가나"; // 조합용 자모로 쓴 "가나"

		assertThat(DisplayNames.normalize(decomposed)).isEqualTo("가나");
	}

	/** 정규화가 먼저다 — 분해 상태의 길이로 세면 보이는 것과 다른 이유로 거절된다. */
	@Test
	void S287_length_is_measured_after_normalizing() {
		assertThat(DisplayNames.normalize("  가나  ")).isEqualTo("가나");
	}

	// ── 길이 ─────────────────────────────────────────────────

	@Test
	void S287_a_single_character_name_is_rejected() {
		assertThatThrownBy(() -> DisplayNames.normalize("가")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void S287_a_name_longer_than_the_limit_is_rejected() {
		String tooLong = "가".repeat(DisplayNames.MAX_LENGTH + 1);

		assertThatThrownBy(() -> DisplayNames.normalize(tooLong)).isInstanceOf(IllegalArgumentException.class);
	}

	/**
	 * <b>코드포인트로 센다.</b> {@code String.length()} 로 세면 서로게이트 쌍이 한 글자를 둘로
	 * 세어 <b>같은 이름이 기준에 따라 길거나 짧아진다.</b>
	 */
	@Test
	void S287_length_counts_code_points_not_utf16_units() {
		// 이모지는 허용목록에 없으므로 어차피 거절되지만, 길이 판정이 먼저 걸리면
		// 이유가 "너무 길다" 로 잘못 나온다.
		assertThatThrownBy(() -> DisplayNames.normalize("연우🌙"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("not allowed");
	}

	// ── 보이지 않는 문자 ──────────────────────────────────────

	/**
	 * 폭 없는 문자와 양방향 제어는 <b>같아 보이는 다른 이름</b>을 만들거나 표시 순서를 뒤집는다.
	 *
	 * <p>S-11 — 우회 표기의 사례가 아니라 방어 규칙이다. 값은 코드포인트로만 적는다.
	 */
	@ParameterizedTest
	@ValueSource(strings = { "연​우", "연‎우", "연‮우", "연﻿우", "연우" })
	void S287_invisible_and_control_characters_are_rejected(String name) {
		assertThatThrownBy(() -> DisplayNames.normalize(name)).isInstanceOf(IllegalArgumentException.class);
	}

	// ── 사칭 ─────────────────────────────────────────────────

	/**
	 * <b>탈퇴 처리가 쓰는 이름을 사용자가 고를 수 없다.</b> 고를 수 있으면 탈퇴한 계정을
	 * 사칭하는 자리가 열린다.
	 */
	@Test
	void S287_the_withdrawn_placeholder_name_is_reserved() {
		assertThatThrownBy(() -> DisplayNames.normalize("탈퇴한 사용자"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("reserved");
	}

	// ── 허용목록 ─────────────────────────────────────────────

	@ParameterizedTest
	@ValueSource(strings = { "연우", "yeonwoo", "달빛 서점", "moon_light", "night-owl", "작가99" })
	void S287_allowed_names_pass(String name) {
		assertThat(DisplayNames.normalize(name)).isEqualTo(name);
	}

	@ParameterizedTest
	@ValueSource(strings = { "연우!", "연우?", "<연우>", "연우/밤", "연우。" })
	void S287_names_outside_the_allowlist_are_rejected(String name) {
		assertThatThrownBy(() -> DisplayNames.normalize(name)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void S287_a_null_name_is_rejected() {
		assertThatThrownBy(() -> DisplayNames.normalize(null)).isInstanceOf(IllegalArgumentException.class);
	}
}

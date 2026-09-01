package com.neowadaeum.common.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * B-31 (#61) — 세 우회 형태가 <b>같은 값으로 수렴하는지</b> 확인한다.
 *
 * <p><b>S-11 — 픽스처는 전부 가상의 문자열이다.</b> 규칙 파일은 "실제 문자열은 테스트 픽스처에만"
 * 이라고 하지만, 공개 레포에 실존 인물명을 적을 이유는 없다. 수렴은 <b>문자 처리의 성질</b>이므로
 * 지어낸 이름으로도 똑같이 검증된다.
 */
class TextNormalizerTests {

	/** 가상의 이름. 첫소리 ㅇ 과 모음 ㅣ 를 포함해 세 우회 형태를 모두 만들 수 있다. */
	private static final String BASE = "이나린";

	/** B-31 DoD — 공백 삽입형이 원형과 같은 값이 된다. */
	@Test
	void B31_whitespace_inserted_form_converges() {
		assertThat(TextNormalizer.normalize("이 나 린")).isEqualTo(TextNormalizer.normalize(BASE));
	}

	/** B-31 DoD — 숫자 치환형이 원형과 같은 값이 된다. */
	@Test
	void B31_digit_substituted_form_converges() {
		assertThat(TextNormalizer.normalize("1나린")).isEqualTo(TextNormalizer.normalize(BASE));
	}

	/** B-31 DoD — 자모 혼용형이 원형과 같은 값이 된다. */
	@Test
	void B31_jamo_mixed_form_converges() {
		assertThat(TextNormalizer.normalize("ㅇㅣ나린")).isEqualTo(TextNormalizer.normalize(BASE));
	}

	/** B-31 DoD — 세 형태를 섞어도 수렴한다. 실제 우회는 한 가지만 쓰지 않는다. */
	@Test
	void B31_combined_evasion_forms_converge() {
		assertThat(TextNormalizer.normalize("ㅇ ㅣ 나 린")).isEqualTo(TextNormalizer.normalize(BASE));
		assertThat(TextNormalizer.normalize("1 나린")).isEqualTo(TextNormalizer.normalize(BASE));
	}

	/** 전각 문자도 반각과 같은 값이 된다. NFKC 단계가 없으면 치환표가 이것을 놓친다. */
	@Test
	void B31_fullwidth_form_converges() {
		assertThat(TextNormalizer.normalize("１나린")).isEqualTo(TextNormalizer.normalize(BASE));
	}

	/** 문장부호와 폭 없는 문자는 제거된다. */
	@Test
	void B31_punctuation_and_zero_width_characters_are_removed() {
		assertThat(TextNormalizer.normalize("이-나_린")).isEqualTo(TextNormalizer.normalize(BASE));
		assertThat(TextNormalizer.normalize("이​나​린")).isEqualTo(TextNormalizer.normalize(BASE));
	}

	/** 끝소리 ㅇ 은 소리가 있으므로 지우지 않는다. 지우면 서로 다른 말이 같은 값이 된다. */
	@Test
	void B31_final_ieung_is_preserved_because_it_carries_sound() {
		assertThat(TextNormalizer.normalize("강")).isNotEqualTo(TextNormalizer.normalize("가"));
	}

	/** 서로 다른 말은 서로 다른 값이어야 한다. 과수렴은 오탐이 된다. */
	@Test
	void B31_distinct_words_do_not_collide() {
		assertThat(TextNormalizer.normalize("나린")).isNotEqualTo(TextNormalizer.normalize(BASE));
	}

	/** 로캘에 흔들리지 않는다. 기본 로캘을 쓰면 서버 설정에 따라 결과가 달라진다. */
	@Test
	void B31_latin_case_folds_regardless_of_default_locale() {
		assertThat(TextNormalizer.normalize("ABC")).isEqualTo(TextNormalizer.normalize("abc"));
	}

	/** 같은 입력은 항상 같은 값이다. 대조가 성립하려면 결정론이어야 한다 (I-15 와 같은 성질). */
	@Test
	void B31_normalization_is_deterministic() {
		assertThat(TextNormalizer.normalize(BASE)).isEqualTo(TextNormalizer.normalize(BASE));
	}

	/** 대조 대상이 없는 경우다. 예외 상황이 아니다. */
	@Test
	void B31_null_and_blank_yield_an_empty_value() {
		assertThat(TextNormalizer.normalize(null)).isEmpty();
		assertThat(TextNormalizer.normalize("   ")).isEmpty();
	}
}

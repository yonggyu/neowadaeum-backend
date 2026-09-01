package com.neowadaeum.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.common.spi.SafetyCategory;
import com.neowadaeum.common.spi.SafetyClassificationFailedException;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 판정 응답을 읽는 규칙 (B-30, R9.2).
 *
 * <p><b>세는 것은 "무엇을 통과시키지 않는가"다.</b> 형식이 어긋난 응답을 관대하게 읽으면 판정
 * 실패가 통과로 둔갑하고, 그 순간 2단은 있으나 마나가 된다.
 */
class SafetyClassificationFormatTests {

	/** 걸린 것이 없으면 빈 집합이다 — 이것이 유일한 통과 경로다. */
	@Test
	void R9_2_an_empty_verdict_is_an_empty_set() {
		assertThat(SafetyClassificationFormat.parse("{\"categories\": []}")).isEmpty();
	}

	/** 카테고리 표기가 값으로 돌아온다 (§9.2). */
	@Test
	void R9_2_declared_categories_are_read_back() {
		Set<SafetyCategory> categories = SafetyClassificationFormat.parse(
				"{\"categories\": [\"hate_speech\", \"rating_exceeded\"]}");

		assertThat(categories).containsExactlyInAnyOrder(SafetyCategory.HATE_SPEECH, SafetyCategory.RATING_EXCEEDED);
	}

	/**
	 * <b>모르는 이름은 실패다. 무시가 아니다.</b>
	 *
	 * <p>무시하면 <b>판정기가 무엇을 봤는지 모르는 상태</b>가 빈 집합, 즉 통과로 바뀐다.
	 */
	@Test
	void B30_an_unknown_category_name_is_a_classification_failure() {
		assertThatThrownBy(() -> SafetyClassificationFormat.parse("{\"categories\": [\"something_else\"]}"))
				.isInstanceOf(SafetyClassificationFailedException.class);
	}

	/** JSON 이 아니면 실패다. 형식을 못 맞춘 판정기는 판정하지 않은 것이다. */
	@Test
	void B30_a_non_json_response_is_a_classification_failure() {
		assertThatThrownBy(() -> SafetyClassificationFormat.parse("판정을 거부합니다"))
				.isInstanceOf(SafetyClassificationFailedException.class);
	}

	/** 배열이 아니면 실패다 — 필드가 있다는 것만으로 통과시키지 않는다. */
	@Test
	void B30_a_categories_field_that_is_not_an_array_is_a_failure() {
		assertThatThrownBy(() -> SafetyClassificationFormat.parse("{\"categories\": \"hate_speech\"}"))
				.isInstanceOf(SafetyClassificationFailedException.class);
	}

	/** R9.3 — 걸린 것이 있으면 기록에 남을 표기가 만들어진다. */
	@Test
	void R9_3_flags_carry_the_wire_names() {
		assertThat(SafetyClassificationFormat.flags(Set.of(SafetyCategory.MINOR_SEXUAL)))
				.isEqualTo("minor_sexual");
		assertThat(SafetyClassificationFormat.flags(Set.of()))
				.as("걸린 것이 없는데 빈 문자열을 남기면 통계에서 0 과 구분되지 않는다")
				.isNull();
	}

	/** S-3 — 실패 메시지에 판정 대상 원문이 실리지 않는다. */
	@Test
	void S3_the_failure_message_does_not_carry_the_judged_text() {
		String judged = "유나의 연락처는 010-0000-0000 이다";

		assertThatThrownBy(() -> SafetyClassificationFormat.parse(judged))
				.hasMessageNotContaining(judged);
	}
}

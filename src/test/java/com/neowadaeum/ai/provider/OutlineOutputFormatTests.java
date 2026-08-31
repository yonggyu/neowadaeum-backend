package com.neowadaeum.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.ai.schema.OutlineOutputSchemaException;
import org.junit.jupiter.api.Test;

/**
 * 초안 응답의 계약 (#238, B-52).
 *
 * <p><b>형태가 위반이고 개수는 위반이 아니다.</b> 그 경계가 재요청 비용을 정한다 — 개수를 계약에
 * 넣으면 모델이 짧게 답하는 날마다 호출이 두 배가 된다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class OutlineOutputFormatTests {

	private static final OutlineRequest REQUEST = new OutlineRequest("봄의 학교", 5, 3);

	@Test
	void B52_a_well_formed_response_becomes_chapters_and_endings() {
		OutlineResult result = OutlineOutputFormat.parse("""
				{"chapters": [{"title": "전학 온 날", "summary": "교실 문을 열자 시선이 모인다."}],
				 "endings": [{"label": "좋은 끝", "epilogue": "봄이 한 번 더 온다."}]}
				""", REQUEST);

		assertThat(result.chapters()).singleElement().satisfies(chapter -> {
			assertThat(chapter.title()).isEqualTo("전학 온 날");
			assertThat(chapter.summarySeed()).isEqualTo("교실 문을 열자 시선이 모인다.");
		});
		assertThat(result.endings()).singleElement().satisfies(ending -> {
			assertThat(ending.label()).isEqualTo("좋은 끝");
			assertThat(ending.epilogueText()).isEqualTo("봄이 한 번 더 온다.");
		});
	}

	/**
	 * <b>요청보다 적게 와도 유효하다</b> (#238).
	 *
	 * <p>개수는 계약이 아니라 요청이다. 이것을 위반으로 삼으면 재요청이 <b>형식 문제가 아닌
	 * 것</b>에 걸린다.
	 */
	@Test
	void B52_a_short_response_is_not_a_violation() {
		OutlineResult result = OutlineOutputFormat.parse(
				"{\"chapters\": [{\"title\": \"첫 장\", \"summary\": \"무슨 일이 일어난다.\"}], \"endings\": []}",
				REQUEST);

		assertThat(result.chapters()).hasSize(1);
		assertThat(result.endings()).isEmpty();
	}

	/**
	 * <b>요청보다 많이 오면 자른다.</b>
	 *
	 * <p>서버가 개수를 정하고(R7.14) 일일 상한이 그 개수를 전제로 비용을 계산한다 (R8.12).
	 */
	@Test
	void R7_14_extra_items_beyond_the_request_are_dropped() {
		String eight = """
				{"chapters": [{"title":"1","summary":"s"},{"title":"2","summary":"s"},
				              {"title":"3","summary":"s"},{"title":"4","summary":"s"},
				              {"title":"5","summary":"s"},{"title":"6","summary":"s"},
				              {"title":"7","summary":"s"},{"title":"8","summary":"s"}],
				 "endings": []}
				""";

		assertThat(OutlineOutputFormat.parse(eight, REQUEST).chapters()).hasSize(5);
	}

	/** 글이 없으면 {@code null} 이다 — 빈 문자열은 <b>비어 있는 글</b>로 읽힌다. */
	@Test
	void B52_a_blank_body_becomes_null() {
		OutlineResult result = OutlineOutputFormat.parse(
				"{\"chapters\": [], \"endings\": [{\"label\": \"좋은 끝\", \"epilogue\": \"  \"}]}", REQUEST);

		assertThat(result.endings()).singleElement()
				.extracting(OutlineResult.Ending::epilogueText).isNull();
	}

	@Test
	void B52_a_response_that_is_not_json_is_a_violation() {
		assertThatThrownBy(() -> OutlineOutputFormat.parse("초안을 만들어 드릴게요", REQUEST))
				.isInstanceOf(OutlineOutputSchemaException.class);
	}

	/** <b>없는 것과 빈 배열은 다르다.</b> 빠뜨린 것은 형식을 못 맞춘 것이다. */
	@Test
	void B52_a_missing_array_is_a_violation() {
		assertThatThrownBy(() -> OutlineOutputFormat.parse("{\"chapters\": []}", REQUEST))
				.isInstanceOf(OutlineOutputSchemaException.class);
	}

	/**
	 * <b>이름이 없는 항목을 통과시키지 않는다.</b>
	 *
	 * <p>목록에 보일 것이 없는 초안은 작성자 화면에서 <b>빈 줄</b>이 된다 — 그것은 "제안하지
	 * 않았다"가 아니라 "빈 것을 제안했다"로 읽힌다.
	 */
	@Test
	void B52_an_item_without_a_name_is_a_violation() {
		assertThatThrownBy(() -> OutlineOutputFormat.parse(
				"{\"chapters\": [{\"summary\": \"이름이 없다\"}], \"endings\": []}", REQUEST))
				.isInstanceOf(OutlineOutputSchemaException.class);
	}

	/** <b>S-3 — 예외 메시지에 응답 원문을 담지 않는다.</b> 원문은 {@code ai_call_log} 만 갖는다. */
	@Test
	void S3_the_violation_message_does_not_carry_the_response() {
		String secret = "이것은 응답 원문이며 로그로 새면 안 된다";

		assertThatThrownBy(() -> OutlineOutputFormat.parse(secret, REQUEST))
				.isInstanceOf(OutlineOutputSchemaException.class)
				.hasMessageNotContaining(secret);
	}
}

package com.neowadaeum.ai.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.play.port.ParagraphType;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 출력 스키마 파서 (B-21, §5.2).
 *
 * <p><b>고정하는 것은 "무엇을 거부하는가"만이 아니라 "무엇을 거부하지 않는가"다.</b> 파서가
 * 과하게 엄격하면 정상 본문이 25초짜리 재생성을 부르고, 그 비용은 조용히 청구된다.
 */
class TurnOutputParserTests {

	private final TurnOutputParser parser = new TurnOutputParser();

	/** §5.2 예시 그대로. 방어선이 정상 경로를 막으면 우회가 생긴다. */
	@Test
	void S5_2_the_documented_example_parses() {
		TurnOutput output = this.parser.parse("""
				{
				  "speakerName": "유나",
				  "paragraphs": [
				    { "type": "dialogue",  "text": "오늘 수업 끝나고 시간 있어?" },
				    { "type": "narration", "text": "개강 이후 처음으로 유나가 먼저 말을 걸었다." }
				  ],
				  "choices": [
				    { "order": 1, "text": "응, 시간 있어." },
				    { "order": 2, "text": "무슨 일인데?" },
				    { "order": 3, "text": "오늘은 조금 힘들 것 같아." }
				  ],
				  "stateChanges": { "affinity.yuna": 2, "flags.add": ["asked_out"] },
				  "chapterAdvanceSuggested": false,
				  "endingSuggested": null
				}
				""");

		assertThat(output.speakerName()).isEqualTo("유나");
		assertThat(output.paragraphs()).extracting(TurnOutput.Paragraph::type)
				.containsExactly(ParagraphType.DIALOGUE, ParagraphType.NARRATION);
		assertThat(output.choices()).extracting(TurnOutput.Choice::order).containsExactly(1, 2, 3);
		assertThat(output.stateChanges().get("affinity.yuna").intValue()).isEqualTo(2);
		assertThat(output.chapterAdvanceSuggested()).isFalse();
		assertThat(output.endingSuggested()).isNull();
	}

	@Nested
	class 본문 {

		private final TurnOutputParser parser = new TurnOutputParser();

		/** R5.1 — 통 문자열 본문을 읽지 않는다. 이것이 파서가 존재하는 첫째 이유다. */
		@Test
		void R5_1_a_plain_string_body_is_rejected() {
			assertThatThrownBy(() -> this.parser.parse("""
					{"paragraphs": "유나가 우산을 내밀었다.", "choices": [{"order": 1, "text": "받는다"}]}
					"""))
					.isInstanceOf(TurnOutputSchemaException.class)
					.hasMessageContaining("paragraphs must be an array");
		}

		/** R5.1 — 배열이지만 비어 있으면 본문이 없는 것이다. */
		@Test
		void R5_1_an_empty_paragraph_array_is_rejected() {
			assertThatThrownBy(() -> this.parser.parse("""
					{"paragraphs": [], "choices": [{"order": 1, "text": "받는다"}]}
					"""))
					.isInstanceOf(TurnOutputSchemaException.class)
					.hasMessageContaining("must not be empty");
		}

		/**
		 * R5.3 — <b>길이를 거부 사유로 삼지 않는다.</b> 원문이 "프롬프트로 강제"라고 주체를 지정했고,
		 * {@code OUTPUT_SPEC} 이 이미 그 문장을 싣고 있다 (B-20). 서버가 여기서 또 거부하면 문단이
		 * 여섯인 정상 본문이 재생성을 부른다.
		 */
		@Test
		void R5_3_paragraph_count_outside_three_to_five_is_not_a_rejection() {
			assertThatCode(() -> this.parser.parse(body(6))).doesNotThrowAnyException();
			assertThatCode(() -> this.parser.parse(body(1))).doesNotThrowAnyException();
		}

		/** 종류를 열거형으로 둔 것이 검증이다 — 모르는 종류는 렌더링에서 깨진다. */
		@Test
		void S5_2_an_unknown_paragraph_type_is_rejected() {
			assertThatThrownBy(() -> this.parser.parse("""
					{"paragraphs": [{"type": "monologue", "text": "..."}],
					 "choices": [{"order": 1, "text": "받는다"}]}
					"""))
					.isInstanceOf(TurnOutputSchemaException.class)
					.hasMessageContaining("unknown paragraph type")
					.hasMessageContaining("dialogue, narration");
		}

		@Test
		void S5_2_a_blank_paragraph_text_is_rejected() {
			assertThatThrownBy(() -> this.parser.parse("""
					{"paragraphs": [{"type": "narration", "text": "   "}],
					 "choices": [{"order": 1, "text": "받는다"}]}
					"""))
					.isInstanceOf(TurnOutputSchemaException.class)
					.hasMessageContaining("paragraph text");
		}

		private static String body(int paragraphCount) {
			StringBuilder json = new StringBuilder("{\"paragraphs\": [");
			for (int i = 0; i < paragraphCount; i++) {
				json.append((i > 0) ? "," : "").append("{\"type\": \"narration\", \"text\": \"문단 ").append(i)
						.append("\"}");
			}
			return json.append("], \"choices\": [{\"order\": 1, \"text\": \"받는다\"}]}").toString();
		}
	}

	@Nested
	class 화자 {

		private final TurnOutputParser parser = new TurnOutputParser();

		/** R5.2 — nullable 이다. 키가 아예 없어도 같은 뜻이다. */
		@Test
		void R5_2_speaker_name_is_nullable_and_may_be_absent() {
			assertThat(this.parser.parse(withSpeaker("null")).speakerName()).isNull();
			assertThat(this.parser.parse(narrationOnly()).speakerName()).isNull();
			assertThat(this.parser.parse(withSpeaker("\"  \"")).speakerName())
					.as("빈 문자열은 나레이션과 같은 뜻이다 — 화면에 빈 화자 칸을 만들지 않는다")
					.isNull();
		}

		@Test
		void R5_2_a_non_string_speaker_name_is_rejected() {
			assertThatThrownBy(() -> this.parser.parse(withSpeaker("42")))
					.isInstanceOf(TurnOutputSchemaException.class)
					.hasMessageContaining("speakerName");
		}

		private static String withSpeaker(String speakerJson) {
			return """
					{"speakerName": %s, "paragraphs": [{"type": "narration", "text": "눈이 내렸다"}],
					 "choices": [{"order": 1, "text": "받는다"}]}
					""".formatted(speakerJson);
		}

		private static String narrationOnly() {
			return """
					{"paragraphs": [{"type": "narration", "text": "눈이 내렸다"}],
					 "choices": [{"order": 1, "text": "받는다"}]}
					""";
		}
	}

	@Nested
	class 선택지 {

		private final TurnOutputParser parser = new TurnOutputParser();

		/**
		 * R5.4 — 상한 초과는 <b>절단</b>이다. 원문의 "절단하거나 재요청" 중 버릴 것이 있는 쪽이며,
		 * 남는 것은 {@code order} 가 앞선 넷이다.
		 */
		@Test
		void R5_4_more_than_four_choices_are_truncated_not_rejected() {
			TurnOutput output = this.parser.parse("""
					{"paragraphs": [{"type": "narration", "text": "눈이 내렸다"}],
					 "choices": [{"order": 3, "text": "c"}, {"order": 1, "text": "a"},
					             {"order": 5, "text": "e"}, {"order": 2, "text": "b"},
					             {"order": 4, "text": "d"}]}
					""");

			assertThat(output.choices()).extracting(TurnOutput.Choice::order).containsExactly(1, 2, 3, 4);
			assertThat(output.choices()).extracting(TurnOutput.Choice::text)
					.as("절단은 순서대로다 — 응답이 뒤섞여 와도 화면 순서가 흔들리지 않는다")
					.containsExactly("a", "b", "c", "d");
		}

		/** R5.4 — 하한 미달은 절단할 것이 없다. 선택지 0개는 사용자가 아무것도 못 하는 화면이다. */
		@Test
		void R5_4_zero_choices_is_rejected() {
			assertThatThrownBy(() -> this.parser.parse("""
					{"paragraphs": [{"type": "narration", "text": "눈이 내렸다"}], "choices": []}
					"""))
					.isInstanceOf(TurnOutputSchemaException.class)
					.hasMessageContaining("choices must not be empty");
		}

		/** 같은 순서가 둘이면 어느 것이 위인지 정해지지 않고, {@code choiceId} 가 그 좌표로 발급된다. */
		@Test
		void S13_9_duplicate_choice_order_is_rejected() {
			assertThatThrownBy(() -> this.parser.parse("""
					{"paragraphs": [{"type": "narration", "text": "눈이 내렸다"}],
					 "choices": [{"order": 1, "text": "a"}, {"order": 1, "text": "b"}]}
					"""))
					.isInstanceOf(TurnOutputSchemaException.class)
					.hasMessageContaining("duplicate choice order");
		}

		@Test
		void S5_2_choice_order_below_one_is_rejected() {
			assertThatThrownBy(() -> this.parser.parse("""
					{"paragraphs": [{"type": "narration", "text": "눈이 내렸다"}],
					 "choices": [{"order": 0, "text": "a"}]}
					"""))
					.isInstanceOf(TurnOutputSchemaException.class)
					.hasMessageContaining("order must be an integer starting at 1");
		}

		/**
		 * I-1 — 응답에 {@code choiceId} 가 실려 와도 담을 자리가 없다. 발급 주체는 서버다
		 * ({@code ChoiceIdIssuer}).
		 */
		@Test
		void I1_a_provider_supplied_choice_id_has_nowhere_to_land() {
			TurnOutput output = this.parser.parse("""
					{"paragraphs": [{"type": "narration", "text": "눈이 내렸다"}],
					 "choices": [{"order": 1, "text": "a", "choiceId": "1-1-deadbeef", "disabled": true}]}
					""");

			assertThat(TurnOutput.Choice.class.getRecordComponents())
					.as("I-1 · I-11 — 값을 무시하는 코드가 아니라 받을 자리가 없는 것이 보장이다")
					.extracting(java.lang.reflect.RecordComponent::getName)
					.containsExactlyInAnyOrder("order", "text");
			assertThat(output.choices()).hasSize(1);
		}
	}

	@Nested
	class 서버_전용_필드 {

		private final TurnOutputParser parser = new TurnOutputParser();

		/**
		 * I-9 — {@code chapter} · {@code turn} 이 실려 와도 서버가 읽을 방법이 없다.
		 *
		 * <p><b>거부하지 않는 것이 의도다.</b> I-9 가 요구하는 것은 "서버가 그 값을 읽지 않는 것"이고,
		 * 담을 자리가 없다는 사실이 그것을 보장한다. 필드 하나로 턴 전체를 날릴 이유가 없다.
		 */
		@Test
		void I9_chapter_and_turn_have_nowhere_to_land() {
			TurnOutput output = this.parser.parse("""
					{"chapter": 9, "turn": 99, "paragraphs": [{"type": "narration", "text": "눈이 내렸다"}],
					 "choices": [{"order": 1, "text": "받는다"}]}
					""");

			assertThat(TurnOutput.class.getRecordComponents())
					.extracting(java.lang.reflect.RecordComponent::getName)
					.doesNotContain("chapter", "turn");
			assertThat(output.paragraphs()).hasSize(1);
		}

		/** R4.1 · R4.2 — 화이트리스트와 clamp 는 GameState 엔진이 소유한다. 여기서 겹쳐 두지 않는다. */
		@Test
		void R4_1_state_changes_pass_through_untouched() {
			TurnOutput output = this.parser.parse("""
					{"paragraphs": [{"type": "narration", "text": "눈이 내렸다"}],
					 "choices": [{"order": 1, "text": "받는다"}],
					 "stateChanges": {"affinity.yuna": 999, "unknown.key": 1, "flags.add": ["x"]}}
					""");

			assertThat(output.stateChanges().get("affinity.yuna").intValue())
					.as("clamp 은 여기서 하지 않는다 — 같은 규칙이 두 곳에 있으면 갈라진다")
					.isEqualTo(999);
			assertThat(output.stateChanges().has("unknown.key")).isTrue();
		}

		@Test
		void S5_2_missing_state_changes_becomes_an_empty_object() {
			TurnOutput output = this.parser.parse("""
					{"paragraphs": [{"type": "narration", "text": "눈이 내렸다"}],
					 "choices": [{"order": 1, "text": "받는다"}]}
					""");

			assertThat(output.stateChanges().isObject()).isTrue();
			assertThat(output.stateChanges().isEmpty()).isTrue();
		}
	}

	@Nested
	class 형식_자체가_아닐_때 {

		private final TurnOutputParser parser = new TurnOutputParser();

		@Test
		void S5_2_a_non_json_response_is_rejected() {
			assertThatThrownBy(() -> this.parser.parse("죄송합니다, 그 요청은 도와드릴 수 없습니다."))
					.isInstanceOf(TurnOutputSchemaException.class)
					.hasMessageContaining("not valid JSON");
		}

		@Test
		void S5_2_a_json_array_root_is_rejected() {
			assertThatThrownBy(() -> this.parser.parse("[]"))
					.isInstanceOf(TurnOutputSchemaException.class)
					.hasMessageContaining("root must be a JSON object");
		}

		@Test
		void S5_2_an_empty_response_is_rejected() {
			assertThatThrownBy(() -> this.parser.parse("   "))
					.isInstanceOf(TurnOutputSchemaException.class);
		}

		/**
		 * S-3 — 예외는 로그로 흐른다. 응답 원문이 메시지에 실리면 <b>막으려던 것이 로그로 나간다.</b>
		 *
		 * <p>"있어야 할 것"만 단언하면 값이 새어도 통과한다 ({@code .claude/rules/testing.md}).
		 */
		@Test
		void SEC3_the_exception_message_does_not_carry_the_response_body() {
			String secretish = "유나의 연락처는 010-0000-0000 이다";

			List<String> messages = List.of(
					messageOf("이것은 JSON 이 아니다: " + secretish),
					messageOf("{\"paragraphs\": \"%s\", \"choices\": []}".formatted(secretish)),
					messageOf("{\"paragraphs\": [{\"type\": \"narration\", \"text\": \"%s\"}], \"choices\": []}"
							.formatted(secretish)),
					// 열거형 자리라고 안전하지 않다 — 모델이 무엇을 넣을지 모른다.
					messageOf("{\"paragraphs\": [{\"type\": \"%s\", \"text\": \"...\"}], \"choices\": []}"
							.formatted(secretish)),
					messageOf("{\"speakerName\": \"%s\", \"paragraphs\": [], \"choices\": []}".formatted(secretish)));

			assertThat(messages).allSatisfy(message -> assertThat(message).doesNotContain(secretish));
		}

		private String messageOf(String raw) {
			try {
				this.parser.parse(raw);
				throw new AssertionError("스키마 위반이 아니었다: 이 입력은 거부되어야 한다");
			}
			catch (TurnOutputSchemaException ex) {
				return String.valueOf(ex.getMessage());
			}
		}
	}
}

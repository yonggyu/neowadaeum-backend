package com.neowadaeum.authoring.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.ai.prompt.PromptStateVocabularyBudget;
import com.neowadaeum.authoring.UgcLimitProperties;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.common.support.ApproximateTokenCounter;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * §13-81 — <b>원고 하나가 검수 한 번의 크기를 정하지 못한다</b> (R8.12, #380).
 *
 * <p>L1 은 작품의 텍스트를 필드 지도로 펼쳐 한 번에 판정기에 넘긴다. 그 지도의 크기는 챕터·엔딩
 * 개수에 비례하는데 세는 자리가 없었다 — L0 에는 요청당 상한이 있고 L1 에는 없다는 <b>비대칭이
 * 의도인지 누락인지</b> 코드 어디에서도 읽히지 않았다.
 *
 * <p><b>S-11 — 픽스처는 가상의 문자열이다.</b> 여기서 확인하는 것은 판정이 아니라 <b>몇 개까지
 * 지나가는가</b> 다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class DraftScaleGateTests {

	private final UgcLimitProperties limits = UgcLimitProperties.defaults();

	private final DraftScaleGate gate = new DraftScaleGate(this.limits);

	/** 상한만큼은 지나간다 — 울타리는 <b>넘는 것</b>을 막지 닿는 것을 막지 않는다. */
	@Test
	void R8_12_a_manuscript_at_the_cap_still_saves() {
		assertThatCode(() -> this.gate.verify(declaredWith(this.limits.chaptersPerStory(),
				this.limits.endingsPerStory()))).doesNotThrowAnyException();
	}

	/**
	 * <b>챕터가 상한을 넘으면 거절한다</b> (R8.12).
	 *
	 * <p>R8.12 가 작성자당 <b>작품 수</b>에 상한을 둔 것과 같은 종류의 이유이며, 거기서 세지
	 * 않은 축이다 — 만드는 쪽에 상한이 없으면 그 뒤의 모든 비용에 상한이 없다.
	 */
	@Test
	void R8_12_more_chapters_than_the_cap_is_rejected() {
		assertThatThrownBy(
				() -> this.gate.verify(declaredWith(this.limits.chaptersPerStory() + 1, 1)))
				.isInstanceOf(ApiException.class)
				.extracting("errorCode")
				.isEqualTo(ErrorCode.VALIDATION_ERROR);
	}

	/** 엔딩도 같은 축이다 — 한쪽만 세면 다른 쪽이 그 상한을 무의미하게 만든다. */
	@Test
	void R8_12_more_endings_than_the_cap_is_rejected() {
		assertThatThrownBy(
				() -> this.gate.verify(declaredWith(1, this.limits.endingsPerStory() + 1)))
				.isInstanceOf(ApiException.class);
	}

	/**
	 * <b>어느 목록이 몇 개까지인지 알린다</b> (§13-81).
	 *
	 * <p>세이프티 임계가 아니므로 가리지 않는다 — 가리면 작성자는 <b>몇 개를 지워야 하는지</b>
	 * 알 수 없고, 지웠다 넣었다 하며 같은 400 을 반복해서 받는다.
	 *
	 * <p><b>자리는 {@code details.fields} 다</b> (§13-96, #478). 가리키는 것이 요청 본문의 실제
	 * 칸이므로 다른 검증 실패와 같은 모양으로 온다 — {@code max} 는 그 항목 안에 남는다.
	 */
	@Test
	@SuppressWarnings("unchecked")
	void S13_96_the_rejection_names_the_list_and_the_cap_in_the_fields_array() {
		ApiException thrown = catchApiException(
				() -> this.gate.verify(declaredWith(this.limits.chaptersPerStory() + 1, 1)));

		assertThat(thrown.details()).containsOnlyKeys("fields");
		assertThat((java.util.List<java.util.Map<String, Object>>) thrown.details().get("fields"))
				.singleElement()
				.satisfies(entry -> assertThat(entry).containsEntry("field", "chapters")
						.containsEntry("max", this.limits.chaptersPerStory())
						.containsKey("reason"));
	}

	/**
	 * <b>어휘 게이트와 다른 축이다</b> (§13-76 · §13-81, #367 · #380).
	 *
	 * <p>인물도 플래그도 없는 원고는 프롬프트 어휘 블록을 거의 만들지 않으므로
	 * {@link DraftVocabularyGate} 를 <b>그대로 지나간다.</b> 챕터·엔딩은 그 블록에 실리지 않기
	 * 때문이며, 그래서 세는 자리가 따로 필요하다 — 두 상한이 서로를 모르는 상태가 #367 이 겪은
	 * 실패다.
	 */
	@Test
	void S13_81_the_vocabulary_gate_does_not_see_this_axis() {
		String payload = payloadOf(this.limits.chaptersPerStory() + 1, 1);
		DraftStoryDefinition.Declared declared = DraftStoryDefinition.validateConditions(payload);
		DraftVocabularyGate vocabulary =
				new DraftVocabularyGate(new PromptStateVocabularyBudget(new ApproximateTokenCounter()));

		assertThatCode(() -> vocabulary.verify(declared.vocabulary())).doesNotThrowAnyException();
		assertThatThrownBy(() -> this.gate.verify(declared)).isInstanceOf(ApiException.class);
	}

	/**
	 * <b>세는 것은 작성자가 적은 엔딩이다</b> (§13-81).
	 *
	 * <p>서버는 기본 엔딩 하나를 발행 시점에 더한다 (§13-16). 그것까지 세면 상한에 닿은 원고는
	 * <b>작성자가 지울 수 없는 한 줄</b> 때문에 거절되고, 무엇을 지워야 하는지 알 수 없다.
	 */
	@Test
	void S13_81_the_ending_the_server_adds_is_not_counted() {
		DraftStoryDefinition.Declared declared = DraftStoryDefinition
				.validateConditions(payloadOf(1, this.limits.endingsPerStory()));

		assertThat(declared.endingCount()).isEqualTo(this.limits.endingsPerStory());
		assertThat(DraftStoryDefinition.from(java.util.UUID.randomUUID(),
				payloadOf(1, this.limits.endingsPerStory())).definition().endings())
				.hasSize(this.limits.endingsPerStory() + 1);
	}

	// ── 픽스처 ──────────────────────────────────────────────

	/** 게이트가 보는 것은 개수뿐이다 — 원고를 실제로 읽어 세운다. */
	private static DraftStoryDefinition.Declared declaredWith(int chapters, int endings) {
		return DraftStoryDefinition.validateConditions(payloadOf(chapters, endings));
	}

	private static String payloadOf(int chapters, int endings) {
		return """
				{"title":"봄의 학교","settingDetail":"어느 봄",%s,%s}"""
				.formatted(rows("chapters", "title", chapters), rows("endings", "label", endings));
	}

	private static String rows(String list, String field, int count) {
		return IntStream.rangeClosed(1, count)
				.mapToObj(index -> "{\"%s\":\"%d번째\"}".formatted(field, index))
				.collect(Collectors.joining(",", "\"" + list + "\":[", "]"));
	}

	private static ApiException catchApiException(Runnable call) {
		try {
			call.run();
			throw new AssertionError("거절되지 않았다");
		}
		catch (ApiException ex) {
			return ex;
		}
	}
}

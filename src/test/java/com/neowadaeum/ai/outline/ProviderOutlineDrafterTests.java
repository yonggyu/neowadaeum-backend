package com.neowadaeum.ai.outline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.ai.provider.OutlineRequest;
import com.neowadaeum.ai.provider.OutlineResult;
import com.neowadaeum.ai.provider.TurnOnlyStoryProvider;
import com.neowadaeum.common.spi.OutlineDraft;
import com.neowadaeum.common.spi.OutlineDraftFailedException;
import com.neowadaeum.common.spi.OutlineDraftRequest;
import com.neowadaeum.play.port.ProviderCallFailedException;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * B-52 — <b>번호와 구조는 서버가 붙인다</b> (R7.14).
 *
 * <p>모델에게 번호를 매기게 하면 <b>빠지거나 겹친 번호</b>가 그대로 원고에 들어온다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class ProviderOutlineDrafterTests {

	/** 번호가 1부터 빠짐없이 붙는다. */
	@Test
	void R7_14_numbers_are_assigned_by_the_server() {
		ProviderOutlineDrafter drafter = drafterReturning(new OutlineResult(
				List.of(chapter("첫 장"), chapter("둘째 장"), chapter("셋째 장")),
				List.of(ending("좋은 끝"), ending("쓸쓸한 끝"))));

		OutlineDraft draft = drafter.draft(new OutlineDraftRequest("봄의 학교", 3, 2));

		assertThat(draft.chapters()).extracting(OutlineDraft.Chapter::chapterNo)
				.containsExactly(1, 2, 3);
		assertThat(draft.endings()).extracting(OutlineDraft.Ending::endingNo).containsExactly(1, 2);
	}

	/**
	 * <b>번호를 담을 자리가 Provider 쪽에 없다</b> (R7.14).
	 *
	 * <p>프롬프트로 부탁하는 것과 담을 자리를 두지 않는 것은 다르다 — 부탁은 어겨질 수 있다.
	 */
	@Test
	void R7_14_the_provider_result_has_no_place_for_a_number() {
		assertThat(OutlineResult.Chapter.class.getRecordComponents())
				.extracting(java.lang.reflect.RecordComponent::getName)
				.containsExactly("title", "summarySeed");
		assertThat(OutlineResult.Ending.class.getRecordComponents())
				.extracting(java.lang.reflect.RecordComponent::getName)
				.containsExactly("label", "epilogueText");
	}

	/** 이름과 글이 그대로 옮겨진다 — 여기서 잘라 붙이지 않는다. */
	@Test
	void R7_14_the_title_and_the_body_are_carried_as_they_are() {
		ProviderOutlineDrafter drafter = drafterReturning(new OutlineResult(
				List.of(new OutlineResult.Chapter("전학 온 날", "교실 문을 열자 시선이 모인다.")),
				List.of()));

		assertThat(drafter.draft(new OutlineDraftRequest("봄의 학교", 1, 1)).chapters())
				.singleElement()
				.satisfies(chapter -> {
					assertThat(chapter.title()).isEqualTo("전학 온 날");
					assertThat(chapter.summarySeed()).isEqualTo("교실 문을 열자 시선이 모인다.");
				});
	}

	/** 글이 없으면 {@code null} 이다 — 빈 문자열은 <b>비어 있는 글</b>로 읽힌다. */
	@Test
	void R7_14_an_ending_without_a_body_carries_null() {
		ProviderOutlineDrafter drafter = drafterReturning(
				new OutlineResult(List.of(), List.of(new OutlineResult.Ending("좋은 끝", null))));

		assertThat(drafter.draft(new OutlineDraftRequest("봄의 학교", 1, 1)).endings())
				.singleElement()
				.extracting(OutlineDraft.Ending::epilogueText).isNull();
	}

	/**
	 * <b>모자라면 모자란 대로 돌려준다.</b>
	 *
	 * <p>부족한 자리를 빈 문장으로 채우면 작성자는 그것을 <b>AI 가 제안한 것</b>으로 읽는다 —
	 * 없는 제안과 빈 제안은 다르다.
	 */
	@Test
	void R7_14_a_short_result_is_not_padded() {
		ProviderOutlineDrafter drafter = drafterReturning(
				new OutlineResult(List.of(chapter("첫 장")), List.of()));

		OutlineDraft draft = drafter.draft(new OutlineDraftRequest("봄의 학교", 5, 3));

		assertThat(draft.chapters()).hasSize(1);
		assertThat(draft.endings()).isEmpty();
	}

	/**
	 * <b>Provider 쪽 실패는 SPI 의 이름을 달고 나간다</b> (#238).
	 *
	 * <p>{@code authoring} 은 {@code ai} 도 {@code play :: port} 도 보지 않는다 (§5.4). 여기서
	 * 바꾸지 않으면 그 실패는 경계를 넘어가 500 이 되고, <b>작성자는 자기가 무엇을 잘못했는지
	 * 묻는다.</b>
	 */
	@Test
	void B52_a_provider_failure_crosses_the_boundary_with_an_spi_name() {
		ProviderOutlineDrafter drafter = drafterThrowing(
				() -> new ProviderCallFailedException("anthropic outline call failed"));

		assertThatThrownBy(() -> drafter.draft(new OutlineDraftRequest("봄의 학교", 5, 3)))
				.isInstanceOf(OutlineDraftFailedException.class)
				.hasCauseInstanceOf(ProviderCallFailedException.class);
	}

	/** <b>조건이 초안에 없다</b> (R7.16) — 조건은 템플릿에서 고른다. */
	@Test
	void R7_16_the_draft_carries_no_condition() {
		assertThat(OutlineDraft.Chapter.class.getRecordComponents())
				.extracting(java.lang.reflect.RecordComponent::getName)
				.containsExactly("chapterNo", "title", "summarySeed");
		assertThat(OutlineDraft.Ending.class.getRecordComponents())
				.extracting(java.lang.reflect.RecordComponent::getName)
				.containsExactly("endingNo", "label", "epilogueText");
	}

	/** <b>회원 식별정보를 담을 자리가 없다</b> (I-3). 필터링이 아니라 형태가 보장이다. */
	@Test
	void I3_the_request_has_no_place_for_member_identity() {
		assertThat(OutlineDraftRequest.class.getRecordComponents())
				.extracting(java.lang.reflect.RecordComponent::getName)
				.containsExactly("worldPrompt", "chapterCount", "endingCount");
	}

	/** 세계관이 없으면 만들 것이 없다. */
	@Test
	void R7_14_a_request_without_a_world_is_refused() {
		assertThatThrownBy(() -> new OutlineDraftRequest("  ", 5, 3))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private static OutlineResult.Chapter chapter(String title) {
		return new OutlineResult.Chapter(title, "무슨 일이 일어나는지");
	}

	private static OutlineResult.Ending ending(String label) {
		return new OutlineResult.Ending(label, "마지막에 붙는 글");
	}

	private static ProviderOutlineDrafter drafterReturning(OutlineResult result) {
		return drafter(() -> result);
	}

	private static ProviderOutlineDrafter drafterThrowing(Supplier<RuntimeException> failure) {
		return drafter(() -> {
			throw failure.get();
		});
	}

	private static ProviderOutlineDrafter drafter(Supplier<OutlineResult> outline) {
		return new ProviderOutlineDrafter(new TurnOnlyStoryProvider() {
			@Override
			public String providerId() {
				return "stub";
			}

			@Override
			public com.neowadaeum.play.port.GeneratedTurn generateTurn(
					com.neowadaeum.play.port.TurnRequest request) {
				throw new UnsupportedOperationException("이 테스트는 턴을 만들지 않는다");
			}

			@Override
			public OutlineResult draftOutline(OutlineRequest request) {
				return outline.get();
			}
		});
	}
}

package com.neowadaeum.safety.l2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.authoring.blocklist.InMemoryBlocklistQuery;
import com.neowadaeum.common.spi.BlocklistEntry;
import com.neowadaeum.common.spi.SafetyCategory;
import com.neowadaeum.common.spi.SafetyClassificationFailedException;
import com.neowadaeum.common.spi.SafetyClassifier;
import com.neowadaeum.common.support.TextNormalizer;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * R9.2 의 2단 구성 (B-30).
 *
 * <p><b>세는 것이 둘이다</b> — 무엇이 통과하지 못하는가, 그리고 <b>2단이 몇 번 불렸는가.</b>
 * 두 번째를 세지 않으면 "즉시차단인데도 유료 호출이 나가는" 상태가 초록으로 보인다.
 *
 * <p><b>S-11 — 실제 차단 항목을 쓰지 않는다.</b> 픽스처의 문자열은 아무 의미 없는 값이다.
 */
class SafetyL2JudgeTests {

	private static final String LISTED = "픽스처항목";

	private static final List<String> PARAGRAPHS = List.of("평범한 문단이다.");

	private static final List<String> CHOICES = List.of("계속한다");

	/** 나레이션만인 턴 — 판정할 화자 이름이 없다 (R5.2). */
	private static final List<String> NO_SPEAKERS = List.of();

	/** 세는 판정기. 몇 번 불렸는지가 §9.2 의 비용 규칙을 지키는지를 말해 준다. */
	private static final class CountingClassifier implements SafetyClassifier {

		private final AtomicInteger calls = new AtomicInteger();

		private final Set<SafetyCategory> verdict;

		private final boolean failing;

		private CountingClassifier(Set<SafetyCategory> verdict, boolean failing) {
			this.verdict = verdict;
			this.failing = failing;
		}

		static CountingClassifier finding(SafetyCategory... categories) {
			return new CountingClassifier(Set.of(categories), false);
		}

		static CountingClassifier failing() {
			return new CountingClassifier(Set.of(), true);
		}

		@Override
		public Set<SafetyCategory> classify(com.neowadaeum.common.spi.SafetyClassificationRequest request) {
			this.calls.incrementAndGet();
			if (this.failing) {
				throw new SafetyClassificationFailedException("판정 실패");
			}
			return this.verdict;
		}
	}

	private static RuleBasedSafetyJudge rulesFinding(SafetyCategory category) {
		return new RuleBasedSafetyJudge(new InMemoryBlocklistQuery(
				List.of(new BlocklistEntry(TextNormalizer.normalize(LISTED), category))));
	}

	private static RuleBasedSafetyJudge rulesFindingNothing() {
		return new RuleBasedSafetyJudge(new InMemoryBlocklistQuery(List.of()));
	}

	/** 두 단 모두 아무것도 못 찾으면 통과다. */
	@Test
	void R9_2_a_clean_turn_passes_both_stages() {
		CountingClassifier classifier = CountingClassifier.finding();

		SafetyJudgement judgement = new SafetyL2Judge(rulesFindingNothing(), classifier)
				.judge(PARAGRAPHS, CHOICES, NO_SPEAKERS);

		assertThat(judgement.outcome()).isEqualTo(SafetyOutcome.PASS);
		assertThat(classifier.calls).hasValue(1);
	}

	/**
	 * <b>1단이 못 잡는 것을 2단이 잡는다 — 이 작업의 이유다.</b>
	 *
	 * <p>블록리스트에 없는 문자열이라 1단은 통과시킨다. 그것을 2단이 걸어 재생성 대상이 된다.
	 */
	@Test
	void R9_2_stage_two_catches_what_the_blocklist_cannot() {
		SafetyJudgement judgement = new SafetyL2Judge(rulesFindingNothing(),
				CountingClassifier.finding(SafetyCategory.RATING_EXCEEDED))
				.judge(PARAGRAPHS, CHOICES, NO_SPEAKERS);

		assertThat(judgement.outcome()).isEqualTo(SafetyOutcome.REGENERATE);
		assertThat(judgement.categories()).containsExactly(SafetyCategory.RATING_EXCEEDED);
	}

	/**
	 * <b>§9.2 — 즉시차단이면 2단을 부르지 않는다.</b>
	 *
	 * <p>결과가 이미 가장 강하다. 부르면 판정은 그대로이고 <b>유료 호출만 는다.</b>
	 */
	@Test
	void S9_2_an_immediate_block_does_not_call_stage_two() {
		CountingClassifier classifier = CountingClassifier.finding();

		SafetyJudgement judgement = new SafetyL2Judge(rulesFinding(SafetyCategory.MINOR_SEXUAL), classifier)
				.judge(List.of(LISTED + " 가 나온다."), CHOICES, NO_SPEAKERS);

		assertThat(judgement.outcome()).isEqualTo(SafetyOutcome.BLOCK);
		assertThat(classifier.calls).as("즉시차단인데 2단이 불렸다").hasValue(0);
	}

	/**
	 * <b>2단이 즉시차단 카테고리를 찾으면 재생성이 차단으로 올라간다</b> (§9.2).
	 *
	 * <p>약한 쪽을 따르면 즉시차단이 사실상 사라진다 — 1단이 재생성으로 본 턴이 그대로 한 번 더
	 * 생성된다.
	 */
	@Test
	void S9_2_stage_two_can_escalate_a_regeneration_into_a_block() {
		SafetyJudgement judgement = new SafetyL2Judge(rulesFinding(SafetyCategory.RATING_EXCEEDED),
				CountingClassifier.finding(SafetyCategory.NON_CONSENSUAL))
				.judge(List.of(LISTED + " 가 나온다."), CHOICES, NO_SPEAKERS);

		assertThat(judgement.outcome()).isEqualTo(SafetyOutcome.BLOCK);
		assertThat(judgement.categories())
				.containsExactlyInAnyOrder(SafetyCategory.RATING_EXCEEDED, SafetyCategory.NON_CONSENSUAL);
	}

	/**
	 * <b>2단이 판정하지 못하면 차단한다</b> (fail-closed).
	 *
	 * <p>"판정하지 못했다"는 "안전하다"가 아니다. 통과시키면 판정기를 끄는 것만으로 2단이
	 * 사라진다 (ADR-0002 와 같은 성질).
	 */
	@Test
	void B30_a_failed_classification_blocks_instead_of_passing() {
		SafetyJudgement judgement = new SafetyL2Judge(rulesFindingNothing(), CountingClassifier.failing())
				.judge(PARAGRAPHS, CHOICES, NO_SPEAKERS);

		assertThat(judgement.outcome()).isEqualTo(SafetyOutcome.BLOCK);
	}

	/** <b>둘 다 있어야 만들어진다.</b> 하나가 빠진 채로 도는 L2 는 탐지가 절반이다 (R9.2). */
	@Test
	void B30_a_judge_cannot_be_built_with_only_one_stage() {
		assertThatThrownBy(() -> new SafetyL2Judge(rulesFindingNothing(), null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	// ── 마스킹의 경계 (§9.2, §13-21) ─────────────────────────

	/**
	 * <b>1단이 찾은 자리는 가리고 통과한다.</b>
	 *
	 * <p>2단이 아무것도 찾지 못했을 때의 이야기다 — 자리를 아는 탐지만 남은 경우다.
	 */
	@Test
	void R9_2_a_term_the_blocklist_located_is_masked_and_passes() {
		SafetyJudgement judgement = new SafetyL2Judge(
				rulesFinding(SafetyCategory.THIRD_PARTY_PERSONAL_DATA), CountingClassifier.finding())
				.judge(List.of(LISTED + " 가 나온다."), CHOICES, NO_SPEAKERS);

		assertThat(judgement.outcome()).isEqualTo(SafetyOutcome.MASKED);
		assertThat(judgement.masked().paragraphs().getFirst()).doesNotContain(LISTED);
	}

	/**
	 * <b>2단이 찾은 개인정보는 가리지 않는다 — 자리를 모르기 때문이다</b> (§13-21).
	 *
	 * <p>분류기는 카테고리만 돌려준다. 위치를 받는 계약을 만들지 않았고, 모델이 말한 offset 을
	 * 믿고 본문을 잘라내면 <b>모델이 서버의 편집기가 된다.</b> 그래서 결과를 폐기하고 다시 만든다.
	 */
	@Test
	void S13_21_a_semantic_only_detection_is_regenerated_not_masked() {
		SafetyJudgement judgement = new SafetyL2Judge(rulesFindingNothing(),
				CountingClassifier.finding(SafetyCategory.THIRD_PARTY_PERSONAL_DATA))
				.judge(PARAGRAPHS, CHOICES, NO_SPEAKERS);

		assertThat(judgement.outcome()).isEqualTo(SafetyOutcome.REGENERATE);
		assertThat(judgement.masked()).isNull();
	}

	/**
	 * <b>2단이 같은 카테고리를 함께 말하면 1단의 마스킹으로 통과시키지 않는다.</b>
	 *
	 * <p>2단이 본 것은 <b>블록리스트에 적혀 있지 않은 무언가</b>다. 적힌 것만 가리고 통과시키면
	 * 가리지 못한 쪽이 그대로 나간다.
	 */
	@Test
	void S13_21_stage_two_finding_the_same_category_prevents_masking() {
		SafetyJudgement judgement = new SafetyL2Judge(
				rulesFinding(SafetyCategory.THIRD_PARTY_PERSONAL_DATA),
				CountingClassifier.finding(SafetyCategory.THIRD_PARTY_PERSONAL_DATA))
				.judge(List.of(LISTED + " 가 나온다."), CHOICES, NO_SPEAKERS);

		assertThat(judgement.outcome()).isEqualTo(SafetyOutcome.REGENERATE);
		assertThat(judgement.masked()).isNull();
	}

	/** §9.2 — 2단이 재생성 카테고리를 더하면 마스킹으로 내려가지 않는다. */
	@Test
	void S9_2_stage_two_can_escalate_a_masking_into_a_regeneration() {
		SafetyJudgement judgement = new SafetyL2Judge(
				rulesFinding(SafetyCategory.THIRD_PARTY_PERSONAL_DATA),
				CountingClassifier.finding(SafetyCategory.HATE_SPEECH))
				.judge(List.of(LISTED + " 가 나온다."), CHOICES, NO_SPEAKERS);

		assertThat(judgement.outcome()).isEqualTo(SafetyOutcome.REGENERATE);
		assertThat(judgement.masked()).isNull();
	}

	/** R9.6 · S-3 — 판정 결과가 걸린 문자열을 담지 않는다. 카테고리까지다. */
	@Test
	void R9_6_the_judgement_does_not_carry_the_offending_text() {
		SafetyJudgement judgement = new SafetyL2Judge(rulesFinding(SafetyCategory.HATE_SPEECH),
				CountingClassifier.finding())
				.judge(List.of(LISTED + " 가 나온다."), CHOICES, NO_SPEAKERS);

		assertThat(judgement.toString()).doesNotContain(LISTED);
	}

	// ── #243 화자 이름 ──────────────────────────────────────

	/**
	 * <b>화자 이름도 판정 대상이다</b> (I-2, #243).
	 *
	 * <p>모델이 만든 문자열이고 {@code turn.speaker_name} 으로 저장돼 사용자에게 도달한다
	 * (R5.2). 본문과 선택지가 깨끗해도 이름 하나가 검수를 지나지 않으면 <b>검수를 거치지 않고
	 * 화면에 닿는 자리</b>가 남는다.
	 */
	@Test
	void I2_a_speaker_name_is_screened_like_any_other_text() {
		SafetyJudgement judgement = new SafetyL2Judge(
				rulesFinding(SafetyCategory.MINOR_SEXUAL), CountingClassifier.finding())
				.judge(PARAGRAPHS, CHOICES, List.of(LISTED));

		assertThat(judgement.outcome()).isEqualTo(SafetyOutcome.BLOCK);
		assertThat(judgement.categories()).contains(SafetyCategory.MINOR_SEXUAL);
	}

	/** <b>즉시차단은 화자 이름에서도 즉시차단이다</b> — 2단을 부르지 않는다 (§9.2). */
	@Test
	void R9_2_an_immediate_block_in_a_speaker_name_skips_stage_two() {
		CountingClassifier classifier = CountingClassifier.finding();

		new SafetyL2Judge(rulesFinding(SafetyCategory.REAL_PERSON_HARM), classifier)
				.judge(PARAGRAPHS, CHOICES, List.of(LISTED));

		assertThat(classifier.calls).hasValue(0);
	}

	/**
	 * <b>화자 이름은 가리지 않는다 — 재생성으로 올린다</b> (#243 의 결정, §13-46 과 다른 자리).
	 *
	 * <p>1단은 이름 안의 자리를 알므로 가릴 수는 있다. 그러나 이름을 통째로 가린 대사는
	 * <b>누가 말했는지가 사라진 대사</b>이고, 그것은 본문의 뜻이 달라지는 일이다 — 마스킹이
	 * 아니라 훼손이다.
	 */
	@Test
	void R9_2_a_listed_speaker_name_is_regenerated_not_masked() {
		SafetyJudgement judgement = new SafetyL2Judge(
				rulesFinding(SafetyCategory.THIRD_PARTY_PERSONAL_DATA), CountingClassifier.finding())
				.judge(PARAGRAPHS, CHOICES, List.of(LISTED));

		assertThat(judgement.outcome()).isEqualTo(SafetyOutcome.REGENERATE);
		assertThat(judgement.masked()).isNull();
	}

	/**
	 * <b>본문의 마스킹은 그대로다</b> (§13-46).
	 *
	 * <p>#243 이 바꾼 것은 화자 이름의 처리이고, <b>자리를 아는 본문을 가린 뒤 통과시키는</b>
	 * 기존 정책은 건드리지 않았다.
	 */
	@Test
	void R9_2_masking_the_body_still_passes_when_no_speaker_is_listed() {
		SafetyJudgement judgement = new SafetyL2Judge(
				rulesFinding(SafetyCategory.THIRD_PARTY_PERSONAL_DATA), CountingClassifier.finding())
				.judge(List.of(LISTED + " 가 나온다."), CHOICES, List.of("이름 없는 사람"));

		assertThat(judgement.outcome()).isEqualTo(SafetyOutcome.MASKED);
		assertThat(judgement.masked().paragraphs()).doesNotContain(LISTED + " 가 나온다.");
	}

	/** <b>2단도 화자 이름을 본다</b> — 1단이 못 잡는 것을 잡는 자리는 이름에도 있다 (R9.2). */
	@Test
	void R9_2_stage_two_receives_the_speaker_names_too() {
		java.util.List<String> seen = new java.util.ArrayList<>();
		SafetyClassifier recording = request -> {
			seen.addAll(request.texts());
			return Set.of();
		};

		new SafetyL2Judge(rulesFindingNothing(), recording)
				.judge(PARAGRAPHS, CHOICES, List.of("서린"));

		assertThat(seen).contains("서린");
	}

	/**
	 * <b>나레이션만인 턴은 1단을 한 번만 부른다.</b>
	 *
	 * <p>판정할 이름이 없는데 블록리스트를 다시 읽으면 비용만 는다 — {@code null} 화자는
	 * 나레이션이라는 R5.2 의 규칙이 여기서도 그대로다.
	 */
	@Test
	void R5_2_a_narration_only_turn_has_no_speaker_to_screen() {
		SafetyJudgement judgement = new SafetyL2Judge(rulesFindingNothing(),
				CountingClassifier.finding())
				.judge(PARAGRAPHS, CHOICES, java.util.Arrays.asList(null, "  "));

		assertThat(judgement.outcome()).isEqualTo(SafetyOutcome.PASS);
	}
}

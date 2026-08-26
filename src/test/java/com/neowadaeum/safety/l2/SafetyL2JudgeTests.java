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
				.judge(PARAGRAPHS, CHOICES);

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
				.judge(PARAGRAPHS, CHOICES);

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
				.judge(List.of(LISTED + " 가 나온다."), CHOICES);

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
				.judge(List.of(LISTED + " 가 나온다."), CHOICES);

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
				.judge(PARAGRAPHS, CHOICES);

		assertThat(judgement.outcome()).isEqualTo(SafetyOutcome.BLOCK);
	}

	/** <b>둘 다 있어야 만들어진다.</b> 하나가 빠진 채로 도는 L2 는 탐지가 절반이다 (R9.2). */
	@Test
	void B30_a_judge_cannot_be_built_with_only_one_stage() {
		assertThatThrownBy(() -> new SafetyL2Judge(rulesFindingNothing(), null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	/** R9.6 · S-3 — 판정 결과가 걸린 문자열을 담지 않는다. 카테고리까지다. */
	@Test
	void R9_6_the_judgement_does_not_carry_the_offending_text() {
		SafetyJudgement judgement = new SafetyL2Judge(rulesFinding(SafetyCategory.HATE_SPEECH),
				CountingClassifier.finding())
				.judge(List.of(LISTED + " 가 나온다."), CHOICES);

		assertThat(judgement.toString()).doesNotContain(LISTED);
	}
}

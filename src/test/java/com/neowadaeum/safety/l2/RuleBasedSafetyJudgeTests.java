package com.neowadaeum.safety.l2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.authoring.blocklist.InMemoryBlocklistQuery;
import com.neowadaeum.common.spi.BlocklistEntry;
import com.neowadaeum.common.spi.BlocklistQuery;
import com.neowadaeum.common.spi.SafetyCategory;
import com.neowadaeum.common.support.TextNormalizer;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * S-8 (#61) — L2 가 <b>실제로 차단하는지</b>, 그리고 <b>고장났을 때 통과시키지 않는지</b> 확인한다.
 *
 * <p><b>S-11 — 픽스처는 전부 가상의 문자열이다.</b> 판정은 정규화 값끼리의 대조이므로 지어낸
 * 문자열로도 똑같이 검증된다. 실존 인물명이나 실제 IP 명을 공개 레포에 적을 이유가 없다.
 *
 * <p>컨테이너가 필요 없다. 판정기는 순수 로직이고 빠른 루프에서 돌아야 한다 (ADR-0001).
 */
class RuleBasedSafetyJudgeTests {

	/** 가상의 이름. 정규화를 거쳐 항목으로 넣는다 — R2.5 는 정규화끼리 비교하도록 규정한다. */
	private static final String FICTIONAL_NAME = "이나린";

	private static final String FICTIONAL_TITLE = "달빛기사단";

	private static RuleBasedSafetyJudge judgeWith(BlocklistEntry... entries) {
		return new RuleBasedSafetyJudge(new InMemoryBlocklistQuery(List.of(entries)));
	}

	private static BlocklistEntry entry(String raw, SafetyCategory category) {
		return new BlocklistEntry(TextNormalizer.normalize(raw), category);
	}

	// ── 대조 ────────────────────────────────────────────────

	/** R9.2 — 블록리스트에 걸리면 통과하지 않는다. */
	@Test
	void R9_2_blocklist_hit_is_not_allowed_through() {
		RuleBasedSafetyJudge judge = judgeWith(entry(FICTIONAL_NAME, SafetyCategory.REAL_PERSON_HARM));

		SafetyJudgement judgement = judge.judge(List.of("어제 " + FICTIONAL_NAME + " 을 만났다."), List.of());

		assertThat(judgement.outcome()).isEqualTo(SafetyOutcome.BLOCK);
		assertThat(judgement.categories()).containsExactly(SafetyCategory.REAL_PERSON_HARM);
	}

	/**
	 * R9.2 — <b>우회 표기도 걸린다.</b> 단순 문자열 매칭이었다면 여기서 뚫린다.
	 *
	 * <p>정규화기가 세 형태를 같은 값으로 모으므로 대조가 성립한다 (B-31).
	 */
	@Test
	void R9_2_evasive_spellings_are_caught_by_normalization() {
		RuleBasedSafetyJudge judge = judgeWith(entry(FICTIONAL_NAME, SafetyCategory.REAL_PERSON_HARM));

		assertThat(judge.judge(List.of("이 나 린"), List.of()).blocked()).isTrue();
		assertThat(judge.judge(List.of("1나린"), List.of()).blocked()).isTrue();
		assertThat(judge.judge(List.of("ㅇㅣ나린"), List.of()).blocked()).isTrue();
	}

	/** §9.1 — L2 의 대상은 {@code paragraphs} 와 {@code choices} 둘 다다. */
	@Test
	void S9_1_choices_are_judged_as_well_as_paragraphs() {
		RuleBasedSafetyJudge judge = judgeWith(entry(FICTIONAL_NAME, SafetyCategory.REAL_PERSON_HARM));

		SafetyJudgement judgement = judge.judge(List.of("평범한 오후였다."), List.of(FICTIONAL_NAME + " 에게 간다"));

		assertThat(judgement.blocked()).isTrue();
	}

	/** 걸린 것이 없으면 통과한다. 과차단은 서비스가 되지 않는다. */
	@Test
	void R9_2_clean_text_passes() {
		RuleBasedSafetyJudge judge = judgeWith(entry(FICTIONAL_NAME, SafetyCategory.REAL_PERSON_HARM));

		SafetyJudgement judgement = judge.judge(List.of("창밖에 비가 내린다."), List.of("우산을 편다"));

		assertThat(judgement.outcome()).isEqualTo(SafetyOutcome.PASS);
		assertThat(judgement.categories()).isEmpty();
	}

	/** 빈 블록리스트에서는 매칭이 없다. 이것이 슬라이스의 기본 상태이며 올바른 동작이다. */
	@Test
	void S8_empty_blocklist_matches_nothing() {
		RuleBasedSafetyJudge judge = new RuleBasedSafetyJudge(new InMemoryBlocklistQuery());

		assertThat(judge.judge(List.of(FICTIONAL_NAME), List.of()).outcome()).isEqualTo(SafetyOutcome.PASS);
	}

	// ── 카테고리별 정책 (§9.2) ──────────────────────────────

	/** §9.2 — 재생성 1회 대상 카테고리는 즉시차단이 아니다. */
	@Test
	void S9_2_regenerate_category_does_not_block_immediately() {
		RuleBasedSafetyJudge judge = judgeWith(entry(FICTIONAL_TITLE, SafetyCategory.IP_REPLICATION));

		SafetyJudgement judgement = judge.judge(List.of(FICTIONAL_TITLE + " 의 문장이 걸려 있다."), List.of());

		assertThat(judgement.outcome()).isEqualTo(SafetyOutcome.REGENERATE);
		assertThat(judgement.blocked()).isFalse();
	}

	/**
	 * B-30 DoD — <b>즉시차단에서 재생성이 발생하지 않는다.</b>
	 *
	 * <p>두 카테고리가 함께 걸렸을 때 약한 쪽을 따르면 즉시차단이 사실상 사라진다. 섞이는 경우가
	 * 이 규칙이 실제로 시험되는 유일한 지점이다.
	 */
	@Test
	void B30_immediate_block_wins_when_mixed_with_a_regenerate_category() {
		RuleBasedSafetyJudge judge = judgeWith(
				entry(FICTIONAL_TITLE, SafetyCategory.IP_REPLICATION),
				entry(FICTIONAL_NAME, SafetyCategory.MINOR_SEXUAL));

		SafetyJudgement judgement = judge.judge(
				List.of(FICTIONAL_TITLE + " 과 " + FICTIONAL_NAME), List.of());

		assertThat(judgement.outcome()).isEqualTo(SafetyOutcome.BLOCK);
		assertThat(judgement.categories()).contains(SafetyCategory.MINOR_SEXUAL, SafetyCategory.IP_REPLICATION);
	}

	/** §9.2 — 즉시차단 카테고리 셋이 전부 그렇게 분류돼 있다. 표와 코드가 어긋나면 여기가 깨진다. */
	@Test
	void S9_2_immediate_block_categories_are_classified_as_such() {
		assertThat(SafetyCategory.MINOR_SEXUAL.blocksImmediately()).isTrue();
		assertThat(SafetyCategory.REAL_PERSON_HARM.blocksImmediately()).isTrue();
		assertThat(SafetyCategory.NON_CONSENSUAL.blocksImmediately()).isTrue();

		assertThat(SafetyCategory.IP_REPLICATION.blocksImmediately()).isFalse();
		assertThat(SafetyCategory.RATING_EXCEEDED.blocksImmediately()).isFalse();
		assertThat(SafetyCategory.HATE_SPEECH.blocksImmediately()).isFalse();
	}

	/** §0.2 — 마스킹은 미구현이다. 스텁으로 통과시키지 않는다. */
	@Test
	void S0_2_unimplemented_masking_policy_throws_instead_of_passing() {
		RuleBasedSafetyJudge judge = judgeWith(
				entry(FICTIONAL_NAME, SafetyCategory.THIRD_PARTY_PERSONAL_DATA));

		assertThatThrownBy(() -> judge.judge(List.of(FICTIONAL_NAME), List.of()))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	// ── fail-closed (ADR-0002) ──────────────────────────────

	/**
	 * ADR-0002 — 조회가 실패하면 <b>차단한다.</b>
	 *
	 * <p>세이프티에서 fail-open 은 장애가 곧 검수 우회다. 블록리스트를 못 읽는 상태에서
	 * 통과시키면 블록리스트가 존재하지 않는 것과 같다.
	 */
	@Test
	void ADR0002_lookup_failure_blocks_instead_of_passing() {
		RuleBasedSafetyJudge judge = new RuleBasedSafetyJudge(() -> {
			throw new IllegalStateException("lookup down");
		});

		SafetyJudgement judgement = judge.judge(List.of("평범한 오후였다."), List.of());

		assertThat(judgement.outcome()).isEqualTo(SafetyOutcome.BLOCK);
	}

	/** 조회가 {@code null} 을 돌려주는 것도 실패다. 조용히 "항목 없음"으로 읽지 않는다. */
	@Test
	void ADR0002_null_lookup_result_blocks_instead_of_passing() {
		RuleBasedSafetyJudge judge = new RuleBasedSafetyJudge(() -> null);

		assertThat(judge.judge(List.of("평범한 오후였다."), List.of()).blocked()).isTrue();
	}

	/** ADR-0002 — SPI 없이 판정기를 만들 수 없다. 부팅이 멈추는 것이 의도다. */
	@Test
	void ADR0002_judge_cannot_be_constructed_without_a_blocklist() {
		assertThatThrownBy(() -> new RuleBasedSafetyJudge(null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	// ── 노출 금지 (R9.6, S-3) ───────────────────────────────

	/**
	 * R9.6 · S-11 — 판정 결과에 <b>걸린 문자열이 담기지 않는다.</b>
	 *
	 * <p>담으면 로그·응답 어디로든 흘러갈 통로가 생기고, 어떤 표현이 걸렸는지 알려주는 것은
	 * 우회 학습을 돕는다. 담을 자리가 없는 것이 보장이다.
	 */
	@Test
	void R9_6_judgement_carries_no_matched_text() {
		List<String> components = java.util.Arrays.stream(SafetyJudgement.class.getRecordComponents())
				.map(java.lang.reflect.RecordComponent::getName).toList();

		assertThat(components).containsExactly("outcome", "categories");
		assertThat(components).doesNotContain("matchedText", "reason", "message", "detail");
	}

	// ── 결정론 ──────────────────────────────────────────────

	/** I-15 와 같은 성질 — 같은 입력은 같은 판정이다. */
	@Test
	void S8_judgement_is_deterministic() {
		RuleBasedSafetyJudge judge = judgeWith(entry(FICTIONAL_NAME, SafetyCategory.REAL_PERSON_HARM));
		List<String> paragraphs = List.of(FICTIONAL_NAME + " 이 문을 열었다.");

		assertThat(judge.judge(paragraphs, List.of())).isEqualTo(judge.judge(paragraphs, List.of()));
	}
}

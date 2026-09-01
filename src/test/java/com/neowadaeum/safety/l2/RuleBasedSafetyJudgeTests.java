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

	/** 가상의 타인 — §9.2 의 마스킹 카테고리 픽스처. */
	private static final String FICTIONAL_PERSON = "박서린";

	private static final String FICTIONAL_OTHER_PERSON = "정하람";

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

	// ── 마스킹 (§9.2 — 생성물은 가린 뒤 통과) ────────────────

	/**
	 * §9.2 — <b>걸린 자리만 가리고 통과한다.</b>
	 *
	 * <p>가린 결과를 다시 판정해 통과하는지까지 본다. "가렸다고 믿는 것"과 "실제로 지워진 것"은
	 * 다르며, 확인하는 방법은 같은 대조를 한 번 더 거는 것뿐이다.
	 */
	@Test
	void R9_2_a_third_party_term_is_masked_and_then_passes() {
		RuleBasedSafetyJudge judge = judgeWith(
				entry(FICTIONAL_PERSON, SafetyCategory.THIRD_PARTY_PERSONAL_DATA));

		SafetyJudgement judgement = judge.judge(
				List.of("어제 " + FICTIONAL_PERSON + " 에게 편지를 보냈다."), List.of("답장을 쓴다"));

		assertThat(judgement.outcome()).isEqualTo(SafetyOutcome.MASKED);
		assertThat(judgement.categories()).containsExactly(SafetyCategory.THIRD_PARTY_PERSONAL_DATA);

		String masked = judgement.masked().paragraphs().getFirst();
		assertThat(masked).doesNotContain(FICTIONAL_PERSON).contains("어제").contains("편지를 보냈다.");
		assertThat(judgement.masked().choices()).containsExactly("답장을 쓴다");
		assertThat(judge.judge(List.of(masked), List.of()).outcome()).isEqualTo(SafetyOutcome.PASS);
	}

	/** §9.2 — <b>같은 항목이 여러 번 나와도, 항목이 여럿이어도 전부 가린다.</b> 하나라도 남으면 가린 의미가 없다. */
	@Test
	void R9_2_every_occurrence_of_every_third_party_term_is_masked() {
		RuleBasedSafetyJudge judge = judgeWith(
				entry(FICTIONAL_PERSON, SafetyCategory.THIRD_PARTY_PERSONAL_DATA),
				entry(FICTIONAL_OTHER_PERSON, SafetyCategory.THIRD_PARTY_PERSONAL_DATA));

		SafetyJudgement judgement = judge.judge(List.of(
				FICTIONAL_PERSON + " 이 " + FICTIONAL_OTHER_PERSON + " 을 불렀다.",
				"그러나 " + FICTIONAL_PERSON + " 은 답하지 않았다."), List.of());

		assertThat(judgement.outcome()).isEqualTo(SafetyOutcome.MASKED);
		assertThat(judgement.masked().paragraphs())
				.hasSize(2)
				.allSatisfy(text -> assertThat(text)
						.doesNotContain(FICTIONAL_PERSON)
						.doesNotContain(FICTIONAL_OTHER_PERSON));
		assertThat(judge.judge(judgement.masked().paragraphs(), List.of()).outcome())
				.isEqualTo(SafetyOutcome.PASS);
	}

	/**
	 * §9.2 · R9.2 — <b>우회 표기도 가려진다.</b>
	 *
	 * <p>대조가 정규화 값끼리 이뤄지므로 <b>가릴 자리는 원문 위치로 되돌려야 한다.</b> 되돌리지
	 * 못하면 걸렸는데 그대로 나가거나, 엉뚱한 자리를 지운다.
	 *
	 * <p>정규화가 지우는 문자(공백·문장부호)와 BMP 밖 문자가 섞인 문자열에서도 자리가 어긋나지
	 * 않는지 함께 본다 — 자리 추적은 UTF-16 단위로 걷는다.
	 */
	@Test
	void R9_2_masking_lands_on_the_right_place_in_evasive_and_unicode_text() {
		RuleBasedSafetyJudge judge = judgeWith(
				entry(FICTIONAL_PERSON, SafetyCategory.THIRD_PARTY_PERSONAL_DATA));

		SafetyJudgement judgement = judge.judge(
				List.of("🌙 어젯밤, 박 서 린 님과 골목을 걸었다. 🌙"), List.of());

		assertThat(judgement.outcome()).isEqualTo(SafetyOutcome.MASKED);

		String masked = judgement.masked().paragraphs().getFirst();
		assertThat(masked).contains("🌙").contains("어젯밤").contains("골목을 걸었다");
		assertThat(judge.judge(List.of(masked), List.of()).outcome()).isEqualTo(SafetyOutcome.PASS);
	}

	/** §9.1 — 선택지도 가려진다. 선택지 텍스트도 사용자에게 도달한다. */
	@Test
	void S9_1_a_third_party_term_in_a_choice_is_masked_too() {
		RuleBasedSafetyJudge judge = judgeWith(
				entry(FICTIONAL_PERSON, SafetyCategory.THIRD_PARTY_PERSONAL_DATA));

		SafetyJudgement judgement = judge.judge(
				List.of("전화가 울렸다."), List.of(FICTIONAL_PERSON + " 에게 건다", "받지 않는다"));

		assertThat(judgement.outcome()).isEqualTo(SafetyOutcome.MASKED);
		assertThat(judgement.masked().choices()).hasSize(2);
		assertThat(judgement.masked().choices().getFirst()).doesNotContain(FICTIONAL_PERSON);
		assertThat(judgement.masked().choices().getLast()).isEqualTo("받지 않는다");
	}

	/**
	 * §9.2 — <b>가릴 자리를 모르면 가리지 않는다.</b>
	 *
	 * <p>대조는 받은 문자열을 전부 이어 붙여 수행하므로(#84) 문단 경계에 걸친 표현도 걸린다.
	 * 그러나 그 자리는 <b>어느 한 문단 안에 없다</b> — 그런 탐지는 마스킹 실패이고, 원문을 임의로
	 * 잘라내는 대신 재생성으로 내려간다.
	 */
	@Test
	void R9_2_a_term_split_across_paragraphs_is_detected_but_not_masked() {
		RuleBasedSafetyJudge judge = judgeWith(
				entry(FICTIONAL_PERSON, SafetyCategory.THIRD_PARTY_PERSONAL_DATA));

		SafetyJudgement judgement = judge.judge(List.of("이름은 박서", "린 이었다."), List.of());

		assertThat(judgement.outcome()).isEqualTo(SafetyOutcome.REGENERATE);
		assertThat(judgement.masked()).isNull();
		assertThat(judgement.categories()).containsExactly(SafetyCategory.THIRD_PARTY_PERSONAL_DATA);
	}

	/** §9.2 — <b>즉시차단이 마스킹을 이긴다.</b> 개인정보를 가렸다고 즉시차단 사유가 사라지지 않는다. */
	@Test
	void S9_2_an_immediate_block_wins_over_masking() {
		RuleBasedSafetyJudge judge = judgeWith(
				entry(FICTIONAL_PERSON, SafetyCategory.THIRD_PARTY_PERSONAL_DATA),
				entry(FICTIONAL_NAME, SafetyCategory.MINOR_SEXUAL));

		SafetyJudgement judgement = judge.judge(
				List.of(FICTIONAL_PERSON + " 과 " + FICTIONAL_NAME), List.of());

		assertThat(judgement.outcome()).isEqualTo(SafetyOutcome.BLOCK);
		assertThat(judgement.masked()).isNull();
	}

	/** §9.2 — <b>재생성도 마스킹을 이긴다.</b> 다시 만들어야 할 문단을 가려서 통과시키지 않는다. */
	@Test
	void S9_2_a_regenerate_category_wins_over_masking() {
		RuleBasedSafetyJudge judge = judgeWith(
				entry(FICTIONAL_PERSON, SafetyCategory.THIRD_PARTY_PERSONAL_DATA),
				entry(FICTIONAL_TITLE, SafetyCategory.IP_REPLICATION));

		SafetyJudgement judgement = judge.judge(
				List.of(FICTIONAL_PERSON + " 이 " + FICTIONAL_TITLE + " 를 펼쳤다."), List.of());

		assertThat(judgement.outcome()).isEqualTo(SafetyOutcome.REGENERATE);
		assertThat(judgement.masked()).isNull();
	}

	/** 가렸다고 말하면서 가린 본문이 없으면, 부르는 쪽은 원문을 그대로 내보낸다. 그 조합을 만들 수 없다. */
	@Test
	void S9_2_a_masked_judgement_cannot_exist_without_the_masked_text() {
		assertThatThrownBy(() -> new SafetyJudgement(SafetyOutcome.MASKED,
				java.util.Set.of(SafetyCategory.THIRD_PARTY_PERSONAL_DATA), null))
				.isInstanceOf(IllegalArgumentException.class);
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

		// masked 는 **가린 뒤의 본문**이다 — 사용자에게 도달할 문자열이지 걸린 원문이 아니다.
		assertThat(components).containsExactly("outcome", "categories", "masked");
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

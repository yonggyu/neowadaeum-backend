package com.neowadaeum.safety.l2;

import com.neowadaeum.common.spi.SafetyCategory;
import com.neowadaeum.common.spi.SafetyPolicy;
import java.util.Set;

/**
 * 걸린 카테고리들로부터 처리를 정한다 (§9.2).
 *
 * <p><b>1단과 2단이 같은 표를 본다.</b> 정책 판단이 두 곳에 복제되면 <b>한쪽만 관대해지는</b> 날이
 * 오고, 그 차이는 "블록리스트로 걸리면 차단인데 모델로 걸리면 재생성"처럼 설명할 수 없는 형태로
 * 나타난다. 정책은 카테고리에 붙어 있고(§9.2), 그것을 읽는 방법은 하나여야 한다.
 */
final class CategoryPolicy {

	private CategoryPolicy() {
	}

	/**
	 * 가장 강한 정책을 따른다 — <b>차단 &gt; 재생성 &gt; 마스킹</b>.
	 *
	 * <p>즉시차단이 하나라도 있으면 <b>재생성하지 않는다</b> (§9.2, B-30 DoD). 재생성 대상과 섞였을
	 * 때 약한 쪽을 따르면 즉시차단이 사실상 사라진다.
	 *
	 * <p><b>마스킹이 가장 약하다.</b> 마스킹은 걸린 자리만 지우고 나머지를 통과시키는 처리이므로,
	 * 재생성 대상이 함께 걸렸다면 그 문단은 애초에 다시 만들어야 한다 — 개인정보를 가렸다고 해서
	 * 혐오 표현이 사라지지 않는다.
	 *
	 * <p><b>{@link SafetyOutcome#MASKED} 는 정책이지 결과가 아니다.</b> 실제로 가릴 수 있는지는
	 * 판정기가 확인한다 ({@link RuleBasedSafetyJudge}) — 자리를 모르면 재생성으로 내려간다.
	 */
	static SafetyOutcome decide(Set<SafetyCategory> hits) {
		if (hits.isEmpty()) {
			return SafetyOutcome.PASS;
		}

		if (hits.stream().anyMatch(SafetyCategory::blocksImmediately)) {
			return SafetyOutcome.BLOCK;
		}

		if (hits.stream().anyMatch(category -> category.policy() == SafetyPolicy.REGENERATE_ONCE)) {
			return SafetyOutcome.REGENERATE;
		}

		return SafetyOutcome.MASKED;
	}

	/** 이 카테고리들 중 마스킹으로 처리되는 것이 있는가 (§9.2). */
	static boolean anyMasked(Set<SafetyCategory> categories) {
		return categories.stream().anyMatch(category -> category.policy() == SafetyPolicy.MASK);
	}
}

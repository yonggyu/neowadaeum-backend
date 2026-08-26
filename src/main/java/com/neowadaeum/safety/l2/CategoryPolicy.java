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
	 * 가장 강한 정책을 따른다.
	 *
	 * <p>즉시차단이 하나라도 있으면 <b>재생성하지 않는다</b> (§9.2, B-30 DoD). 재생성 대상과 섞였을
	 * 때 약한 쪽을 따르면 즉시차단이 사실상 사라진다.
	 */
	static SafetyOutcome decide(Set<SafetyCategory> hits) {
		if (hits.isEmpty()) {
			return SafetyOutcome.PASS;
		}

		if (hits.stream().anyMatch(SafetyCategory::blocksImmediately)) {
			return SafetyOutcome.BLOCK;
		}

		if (hits.stream().anyMatch(category -> category.policy() == SafetyPolicy.MASK)) {
			// §9.2 는 마스킹 후 통과를 규정하지만 지금은 탐지 위치(span)를 받지 않는다 (§13-21).
			// 스텁으로 통과시키지 않는다 (§0.2).
			throw new UnsupportedOperationException(
					"masking policy is not implemented — see §9.2 and B-30");
		}

		return SafetyOutcome.REGENERATE;
	}
}

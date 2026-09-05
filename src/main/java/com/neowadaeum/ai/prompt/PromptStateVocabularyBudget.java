package com.neowadaeum.ai.prompt;

import com.neowadaeum.ai.prompt.PromptLayer.BudgetGroup;
import com.neowadaeum.common.spi.StateVocabularyBudget;
import com.neowadaeum.common.support.TokenCounter;
import java.util.Collection;
import java.util.List;

/**
 * {@code STATE VOCABULARY} 예산 판정 (§13-76, #367).
 *
 * <p><b>실제로 실릴 블록을 만들어 센다.</b> 머리글 길이도 연산자 표기도 여기서 다시 적지 않고
 * {@link PlatformPrompts} 가 만든 것을 그대로 쓴다 — 예산을 모사하면 모사가 진짜와 갈라지고,
 * 갈라진 쪽이 통과시키는 순간 이 판정은 <b>없는 것과 같아진다.</b> 문구를 한 줄 고치면 게이트가
 * 함께 움직이는 것이 요점이다.
 *
 * <p><b>운영 계산기를 쓴다</b> (#82). 저장 시점에 통과시킨 원고가 턴에서 실패하면 안 되므로,
 * 두 자리가 같은 계산을 보아야 한다.
 */
public class PromptStateVocabularyBudget implements StateVocabularyBudget {

	private final TokenCounter tokenCounter;

	public PromptStateVocabularyBudget(TokenCounter tokenCounter) {
		if (tokenCounter == null) {
			throw new IllegalArgumentException("tokenCounter is required");
		}
		this.tokenCounter = tokenCounter;
	}

	@Override
	public Usage assess(Collection<String> numericPaths, Collection<String> flags, Collection<String> inventory) {
		String block = PlatformPrompts.stateVocabulary(new PromptContext.StateVocabulary(
				copy(numericPaths), copy(flags), copy(inventory)));
		if (block == null) {
			return new Usage(0);
		}

		int used = this.tokenCounter.count(block);
		int limit = BudgetGroup.STATE_VOCABULARY.maxTokens();

		// 올림이다. 상한을 한 토큰 넘긴 선언이 100% 로 보이면 통과한다.
		return new Usage(Math.ceilDiv(used * 100, limit));
	}

	private static List<String> copy(Collection<String> names) {
		return names == null ? List.of() : List.copyOf(names);
	}
}

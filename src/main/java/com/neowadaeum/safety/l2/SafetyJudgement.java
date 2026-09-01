package com.neowadaeum.safety.l2;

import com.neowadaeum.common.spi.SafetyCategory;
import java.util.Set;

/**
 * L2 판정 하나 (§9.1).
 *
 * <p><b>R9.6 — 사용자 노출용 사유를 담지 않는다.</b> 어떤 표현이 걸렸는지 알려주면 우회 학습을
 * 돕는다. {@code categories} 는 서버 내부 기록용이며(R9.3 — {@code turn.safety_verdict} ·
 * {@code ai_call_log.safety_flags}), 응답 조립 단계에서 밖으로 나가지 않아야 한다.
 *
 * <p><b>걸린 문자열 자체를 담지 않는다.</b> 담으면 로그·응답 어디로든 흘러갈 통로가 생긴다 (S-3, S-11).
 * {@code masked} 는 그 예외가 아니다 — <b>가린 뒤의 본문</b>이며, 그것이 곧 사용자에게 도달할
 * 문자열이다. 원문은 여전히 어디에도 담기지 않는다.
 *
 * @param outcome    판정
 * @param categories 걸린 카테고리들. 통과면 비어 있다
 * @param masked     가린 본문. <b>{@link SafetyOutcome#MASKED} 일 때만 있다</b>
 */
public record SafetyJudgement(SafetyOutcome outcome, Set<SafetyCategory> categories, MaskedText masked) {

	public SafetyJudgement {
		if (outcome == null) {
			throw new IllegalArgumentException("outcome is required");
		}
		categories = Set.copyOf(categories == null ? Set.of() : categories);
		if (outcome == SafetyOutcome.MASKED && masked == null) {
			// 가렸다고 말하면서 가린 본문이 없으면, 부르는 쪽은 원문을 그대로 내보낸다.
			throw new IllegalArgumentException("a masked outcome must carry the masked text (§9.2)");
		}
		if (outcome != SafetyOutcome.MASKED && masked != null) {
			throw new IllegalArgumentException("only a masked outcome carries masked text");
		}
	}

	public SafetyJudgement(SafetyOutcome outcome, Set<SafetyCategory> categories) {
		this(outcome, categories, null);
	}

	public static SafetyJudgement pass() {
		return new SafetyJudgement(SafetyOutcome.PASS, Set.of());
	}

	public boolean blocked() {
		return this.outcome == SafetyOutcome.BLOCK;
	}
}

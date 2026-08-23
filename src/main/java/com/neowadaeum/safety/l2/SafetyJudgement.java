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
 *
 * @param outcome    판정
 * @param categories 걸린 카테고리들. 통과면 비어 있다
 */
public record SafetyJudgement(SafetyOutcome outcome, Set<SafetyCategory> categories) {

	public SafetyJudgement {
		if (outcome == null) {
			throw new IllegalArgumentException("outcome is required");
		}
		categories = Set.copyOf(categories == null ? Set.of() : categories);
	}

	public static SafetyJudgement pass() {
		return new SafetyJudgement(SafetyOutcome.PASS, Set.of());
	}

	public boolean blocked() {
		return this.outcome == SafetyOutcome.BLOCK;
	}
}

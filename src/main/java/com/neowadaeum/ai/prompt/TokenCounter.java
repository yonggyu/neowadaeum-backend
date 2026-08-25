package com.neowadaeum.ai.prompt;

/**
 * 토큰 계산 (§4.3, B-20).
 *
 * <p><b>이 경계가 있는 이유는 셋을 세는 방법이 바뀌기 때문이다.</b> 지금은 벤더 없이 근사하고,
 * 실 Provider 가 붙으면(B-22) 그 벤더의 토큰화를 쓴다. 계산이 코드 전체에 흩어져 있으면 그 교체가
 * 불가능해진다 — 추상화가 정당한 자리다 (§2.5 의 "실제 구현이 둘 이상 존재한다").
 */
public interface TokenCounter {

	/** {@code null} 과 빈 문자열은 0 이다. */
	int count(String text);
}

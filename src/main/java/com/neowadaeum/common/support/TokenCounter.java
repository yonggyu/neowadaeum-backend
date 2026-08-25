package com.neowadaeum.common.support;

/**
 * 토큰 계산 (§4.3, R4.9, B-20 / 소유 결정 #82).
 *
 * <p><b>{@code ai} 가 아니라 {@code common} 이 소유한다 (§5.4).</b> 세는 일이 AI 파이프라인 전용이
 * 아니기 때문이다 — 조립 시점의 예산 판단(§4.3)과 UGC 저장 시점의 길이 검증(R4.9, B-51)이 <b>같은
 * 계산을 써야 한다.</b> 둘이 다르게 세면 저장 시점에 통과한 작품이 조립 시점에 예산을 넘는다.
 *
 * <p>{@code common/spi} 가 아닌 이유는, SPI 가 <b>데이터를 소유한 모듈이 구현을 제공하는</b>
 * 계약이기 때문이다 (ADR-0002, ADR-0003). 토큰 계산에는 소유할 데이터가 없다 —
 * {@code TextNormalizer} 와 같은 자리다.
 *
 * <p><b>인터페이스인 이유</b>는 구현이 실제로 둘 이상이기 때문이다 — 운영의 보수적 근사와
 * 테스트의 고정 계산기. 계수 조정도 예정되어 있다 (B-46).
 */
public interface TokenCounter {

	/** {@code null} 과 빈 문자열은 0 이다. */
	int count(String text);
}

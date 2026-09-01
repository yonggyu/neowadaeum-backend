package com.neowadaeum.common.spi;

/**
 * 블록리스트 항목 한 건 (R2.5).
 *
 * <p><b>{@code normalizedValue} 는 이미 정규화된 값이다.</b> R2.5 는 조회를 "항상 정규화된 값끼리"
 * 하도록 규정한다 — 원문을 담아 두고 판정 때마다 정규화하면 그 비용이 매 턴 붙고, 무엇보다
 * 저장 시점과 판정 시점의 정규화 규칙이 어긋날 수 있다.
 *
 * @param normalizedValue {@code common/support/TextNormalizer} 를 거친 값
 * @param category        걸렸을 때 적용할 카테고리
 */
public record BlocklistEntry(String normalizedValue, SafetyCategory category) {

	public BlocklistEntry {
		if (normalizedValue == null || normalizedValue.isBlank()) {
			throw new IllegalArgumentException("normalizedValue is required");
		}
		if (category == null) {
			throw new IllegalArgumentException("category is required");
		}
	}
}

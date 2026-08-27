package com.neowadaeum.admin;

import java.util.UUID;

/**
 * 블록리스트 항목 (§14).
 *
 * <p><b>정규화 값을 내보내지 않는다.</b> 그것은 대조에만 쓰는 내부 표현이며, 내보내면
 * <b>정규화가 무엇을 어떻게 모으는지</b>가 드러난다 — 그 자체가 우회의 재료다 (S-11).
 */
public record BlocklistEntryResponse(UUID id, String kind, String value, String severity,
		String source) {
}

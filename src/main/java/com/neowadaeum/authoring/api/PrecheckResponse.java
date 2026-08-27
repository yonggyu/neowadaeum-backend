package com.neowadaeum.authoring.api;

import com.neowadaeum.authoring.precheck.PrecheckFinding;
import java.util.List;

/**
 * 검사 결과 (R8.2).
 *
 * <p><b>걸린 항목 자체는 담기지 않는다</b> (R8.7, S-11) — 위치와 분류까지다.
 */
public record PrecheckResponse(String state, List<PrecheckFinding> findings) {
}

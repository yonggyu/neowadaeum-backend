package com.neowadaeum.authoring.api;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * 검사할 필드 (§13.8, R8.1).
 *
 * <p><b>단계 전체가 아니라 바뀐 필드만 보낸다.</b> 입력 중에 debounce 후 부르는 경로이므로
 * (권장 800ms), 매번 원고 전부를 검사하면 <b>타자 한 번이 전 필드 검사</b>가 된다.
 *
 * @param fields 필드 경로 → 값. 예 {@code characters[0].name}
 */
public record PrecheckRequest(@NotEmpty @Size(max = 32) Map<String, @Size(max = 8192) String> fields) {
}

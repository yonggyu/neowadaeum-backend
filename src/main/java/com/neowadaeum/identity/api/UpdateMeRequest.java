package com.neowadaeum.identity.api;

import jakarta.validation.constraints.NotNull;

/**
 * 내 계정 변경 요청 (#271).
 *
 * <p><b>필드가 하나뿐이다.</b> 역할과 상태는 사용자가 정하는 값이 아니며, 여기에 두면
 * <b>스스로 관리자가 되는 경로</b>가 된다 (S-4).
 *
 * <p><b>길이·문자 규칙을 여기에 적지 않는다.</b> 정본은 catalog 도메인의 {@code DisplayNames}
 * 이고, 그 규칙을 요청 DTO 에 복사하면 정본이 둘이 된다 — 갈라지는 순간 한쪽이 통과시킨 이름을
 * 다른 쪽이 거절한다. 여기서는 <b>키가 왔는가</b>만 본다.
 *
 * @param displayName 공개 표시명. {@code null} 이면 400 이다 — 지우는 뜻으로 읽지 않는다
 */
public record UpdateMeRequest(@NotNull String displayName) {
}

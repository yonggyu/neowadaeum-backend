package com.neowadaeum.authoring.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 재검토 요청 (#290, §13-59).
 *
 * <p><b>사유는 필수다.</b> 정지된 작품은 이미 검수 큐에 있으므로 (§13-41), 사유 없는 요청은
 * 검수자에게 <b>아무것도 주지 않는다</b> — 그러면 이 경로는 큐를 다시 채우는 버튼일 뿐이다.
 *
 * <p><b>이 문자열은 검수자만 읽는다</b> (S-11). 응답에도 큐의 한 줄에도 실리지 않으며 AI
 * 페이로드로도 가지 않는다 — 자유 문자열이 새 노출면을 열지 않는 것이 이 자리의 안전이다.
 */
public record AppealRequest(@NotBlank @Size(max = 500) String reason) {
}

package com.neowadaeum.authoring.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 업로드 확정 요청 (#315, §13-65).
 *
 * <p>발급이 돌려준 키를 그대로 되돌려준다. <b>서버는 이 키가 이 원고의 것인지 다시 본다</b> —
 * 발급을 받았다는 사실이 어떤 키든 확정할 수 있다는 뜻은 아니다.
 */
public record ImageCommitRequest(@NotBlank String objectKey) {
}

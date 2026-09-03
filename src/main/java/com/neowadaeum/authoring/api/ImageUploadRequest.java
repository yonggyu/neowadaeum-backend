package com.neowadaeum.authoring.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 업로드 URL 발급 요청 (#315, §13-65).
 *
 * <p><b>파일 이름도 경로도 받지 않는다.</b> 키는 서버가 정한다 — 클라이언트가 경로를 고르면
 * 남의 자리에 덮어쓸 수 있다.
 *
 * @param slot        {@code cover} 또는 {@code portrait}
 * @param contentType {@code image/jpeg} · {@code image/png} · {@code image/webp} 중 하나.
 *                    이 값이 <b>서명에 들어간다</b> — 다른 형식으로 올리면 저장소가 거절한다
 */
public record ImageUploadRequest(@NotBlank String slot, @NotBlank String contentType) {
}

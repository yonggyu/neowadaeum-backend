package com.neowadaeum.authoring.api;

import com.neowadaeum.authoring.image.DraftImageService.IssuedUpload;
import com.neowadaeum.authoring.image.DraftImageStore;
import java.net.URI;
import java.time.Instant;

/**
 * 발급된 업로드 자리 (#315, §13-65).
 *
 * <p><b>브라우저가 이 URL 로 직접 PUT 한다.</b> 요청에 {@code Content-Type} 을 여기 적힌 값
 * 그대로 실어야 한다 — 서명에 들어간 값이라 다르면 저장소가 거절한다.
 *
 * <p><b>올린 뒤 확정을 부른다.</b> 확정 전까지 이 키는 확인된 적이 없는 키다.
 *
 * @param maxBytes 이 자리에 올릴 수 있는 최대 바이트 수
 */
public record ImageUploadResponse(String objectKey, URI uploadUrl, String uploadMethod,
		String contentType, long maxBytes, Instant expiresAt) {

	public static ImageUploadResponse of(IssuedUpload issued) {
		return new ImageUploadResponse(issued.objectKey(), issued.uploadUrl(), "PUT",
				issued.format().contentType(), DraftImageStore.MAX_BYTES, issued.expiresAt());
	}
}

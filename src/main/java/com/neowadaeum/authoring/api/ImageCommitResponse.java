package com.neowadaeum.authoring.api;

import com.neowadaeum.authoring.image.DraftImageService.CommittedImage;

/**
 * 확인된 이미지 (#315, §13-65).
 *
 * <p><b>형식과 크기는 저장소가 말한 값</b>이지 요청이 말한 값이 아니다.
 *
 * <p><b>URL 이 없다.</b> 원고에 적을 값은 {@code objectKey} 이며, 그것만으로는 아무도 이미지를
 * 볼 수 없다 — 버킷은 비공개이고 영구 URL 은 존재하지 않는다 (I-8).
 *
 * @param sizeBytes 저장된 바이트 수
 */
public record ImageCommitResponse(String objectKey, String contentType, long sizeBytes) {

	public static ImageCommitResponse of(CommittedImage image) {
		return new ImageCommitResponse(image.objectKey(), image.format().contentType(),
				image.sizeBytes());
	}
}

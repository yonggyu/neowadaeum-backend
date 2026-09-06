package com.neowadaeum.authoring.image;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 원고 이미지 객체 키 하나 (#315, §13-65).
 *
 * <p><b>키의 문법이 곧 권한 판정이다.</b> 발급이 만드는 모양은 {@code drafts/<draftId>/<자리>/
 * <uuid>.<확장자>} 이고, 그 안에 원고 id 가 들어 있으므로 <b>원고 소유 판정이 곧 경로 판정</b>이
 * 된다 — 접두어만 보면 {@code drafts/<id>/../<남의 원고>} 가 통과한다.
 *
 * <p><b>문법을 한 곳에 둔다</b> (#377). 확정과 열람이 같은 키를 각자 해석하면, 한쪽이 받아들이는
 * 키를 다른 쪽이 거절하는 상태가 조용히 생긴다.
 *
 * @param imageId 파일 이름의 UUID. <b>이미지는 자기 행을 갖지 않으므로</b> 열람 감사가 가리키는
 *     식별자가 이것이다 (§13-78)
 * @param format  확장자가 말하는 형식. <b>중계가 내보내는 {@code Content-Type} 의 출처</b>이며
 *     저장소가 기록한 값이 아니다 — 확장자는 발급이 서명한 형식에서 나왔다 (§13-65)
 */
public record DraftImageKey(String objectKey, UUID imageId, ImageFormat format) {

	/** 발급이 만든 파일 이름. UUID 를 <b>느슨하게</b> 받으면 {@code fromString} 이 대신 터진다. */
	private static final Pattern FILE_NAME = Pattern.compile(
			"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.([a-z]{3,4})");

	/**
	 * 이 원고의 키인가.
	 *
	 * @throws ApiException {@code VALIDATION_ERROR} — 발급이 만든 모양이 아니거나 다른 원고의 키다
	 */
	public static DraftImageKey of(UUID draftId, String objectKey) {
		String[] parts = (objectKey == null) ? new String[0] : objectKey.split("/");
		if (parts.length != 4 || !"drafts".equals(parts[0])
				|| !parts[1].equals(draftId.toString()) || DraftImageSlot.of(parts[2]) == null) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
		Matcher fileName = FILE_NAME.matcher(parts[3]);
		ImageFormat format = fileName.matches() ? ImageFormat.ofExtension(fileName.group(2)) : null;
		if (format == null) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
		return new DraftImageKey(objectKey, UUID.fromString(fileName.group(1)), format);
	}
}

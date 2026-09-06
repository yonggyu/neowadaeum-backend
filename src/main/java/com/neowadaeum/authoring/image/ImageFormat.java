package com.neowadaeum.authoring.image;

import java.util.Locale;

/**
 * 받는 이미지 형식 (#315, §13-65).
 *
 * <p><b>허용 목록이다.</b> 거부 목록으로 두면 새 형식이 조용히 통과한다. <b>확장자도 여기서
 * 정한다</b> — 클라이언트가 보낸 파일 이름은 곧 경로가 되고, 경로를 클라이언트가 정하면 그것은
 * 남의 자리가 된다.
 */
public enum ImageFormat {

	JPEG("image/jpeg", "jpg"),
	PNG("image/png", "png"),
	WEBP("image/webp", "webp");

	private final String contentType;

	private final String extension;

	ImageFormat(String contentType, String extension) {
		this.contentType = contentType;
		this.extension = extension;
	}

	/** 목록에 없으면 {@code null} 이다 — 부르는 쪽이 거절한다. */
	public static ImageFormat ofContentType(String contentType) {
		for (ImageFormat format : values()) {
			if (contentType != null
					&& format.contentType.equals(contentType.trim().toLowerCase(Locale.ROOT))) {
				return format;
			}
		}
		return null;
	}

	/**
	 * 키의 확장자로 되찾는다 (#377, §13-78).
	 *
	 * <p><b>중계가 내보내는 {@code Content-Type} 의 출처가 여기다.</b> 저장소가 기록한 값을
	 * 그대로 흘리면 <b>버킷이 응답 헤더를 정하게</b> 되고, 그때 15세 등급이 걸린 이미지가
	 * 브라우저에서 무엇으로 해석되는지를 서버가 더 이상 결정하지 않는다. 확장자는 발급이
	 * 서명한 형식에서 나왔으므로 (§13-65) <b>업로드 때 서명한 값</b>과 같다.
	 *
	 * @return 목록에 없으면 {@code null}
	 */
	public static ImageFormat ofExtension(String extension) {
		for (ImageFormat format : values()) {
			if (extension != null && format.extension.equals(extension.trim().toLowerCase(Locale.ROOT))) {
				return format;
			}
		}
		return null;
	}

	public String contentType() {
		return this.contentType;
	}

	public String extension() {
		return this.extension;
	}
}

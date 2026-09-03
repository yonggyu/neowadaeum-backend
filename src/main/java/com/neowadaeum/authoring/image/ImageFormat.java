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

	public String contentType() {
		return this.contentType;
	}

	public String extension() {
		return this.extension;
	}
}

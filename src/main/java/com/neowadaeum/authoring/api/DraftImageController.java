package com.neowadaeum.authoring.api;

import com.neowadaeum.authoring.image.DraftImageService;
import com.neowadaeum.common.web.PlayerRefResolver;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 원고 이미지 업로드 (#315, §13-65).
 *
 * <p><b>파일이 서버를 거치지 않는다.</b> 서버는 URL 을 서명해 주고 브라우저가 저장소로 직접
 * 올린다 — 그래서 여기에 {@code multipart} 가 없다.
 *
 * <p><b>남의 원고는 없는 것과 구분되지 않는다</b> (I-8). 판정은 서비스가 한다.
 */
@RestController
@RequestMapping("/api/v1/authoring/drafts/{draftId}/images")
public class DraftImageController {

	private final DraftImageService images;

	private final PlayerRefResolver playerRefs;

	public DraftImageController(DraftImageService images, PlayerRefResolver playerRefs) {
		this.images = images;
		this.playerRefs = playerRefs;
	}

	/** 업로드 URL 을 발급한다. <b>키는 서버가 정한다.</b> */
	@PostMapping
	public ImageUploadResponse issue(@PathVariable UUID draftId,
			@Valid @RequestBody ImageUploadRequest body) {
		return ImageUploadResponse.of(this.images.issue(this.playerRefs.currentPlayerRef(), draftId,
				body.slot(), body.contentType()));
	}

	/** 올린 것을 확정한다. <b>여기서 크기와 형식이 실제로 걸린다</b> — 어긋나면 객체를 지운다. */
	@PostMapping("/commit")
	public ImageCommitResponse commit(@PathVariable UUID draftId,
			@Valid @RequestBody ImageCommitRequest body) {
		return ImageCommitResponse.of(
				this.images.commit(this.playerRefs.currentPlayerRef(), draftId, body.objectKey()));
	}
}

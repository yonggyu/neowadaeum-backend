package com.neowadaeum.authoring.api;

import com.neowadaeum.authoring.draft.DraftService;
import com.neowadaeum.authoring.draft.StoryDraft;
import com.neowadaeum.common.web.PlayerRefResolver;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 작품 만들기 — 원고 (§13.8, B-51).
 *
 * <p><b>남의 원고는 없는 것과 구분되지 않는다</b> (I-8). 판정은 서비스가 하고 여기서는
 * 그 결과를 그대로 내보낸다 — 컨트롤러가 소유를 판단하기 시작하면 경로마다 그 판단이 흩어진다.
 */
@RestController
@RequestMapping("/api/v1/authoring/drafts")
public class DraftController {

	private final DraftService drafts;

	private final PlayerRefResolver playerRefs;

	public DraftController(DraftService drafts, PlayerRefResolver playerRefs) {
		this.drafts = drafts;
		this.playerRefs = playerRefs;
	}

	/** 새 원고. 1단계에서 시작한다. */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public DraftResponse create() {
		return DraftResponse.of(this.drafts.create(this.playerRefs.currentPlayerRef()));
	}

	/** 내 원고 목록. */
	@GetMapping
	public List<DraftResponse> list() {
		return this.drafts.list(this.playerRefs.currentPlayerRef()).stream()
				.map(DraftResponse::of).toList();
	}

	@GetMapping("/{draftId}")
	public DraftResponse read(@PathVariable UUID draftId) {
		return DraftResponse.of(this.drafts.read(this.playerRefs.currentPlayerRef(), draftId));
	}

	/**
	 * 단계별 저장 (§8.1).
	 *
	 * <p><b>{@code blocked} 면 앞으로 가지 못한다</b> (R8.3) — 클라이언트 검증에만 의존하지
	 * 않는다. 뒤로 돌아가는 길은 열려 있다.
	 */
	@PatchMapping("/{draftId}")
	public DraftResponse save(@PathVariable UUID draftId, @Valid @RequestBody DraftPatchRequest body) {
		StoryDraft saved = this.drafts.save(this.playerRefs.currentPlayerRef(), draftId, body.step(),
				body.payload());
		return DraftResponse.of(saved);
	}

	/** 지운다. <b>없어도 성공이다</b> — 삭제는 상태를 맞추는 요청이다. */
	@DeleteMapping("/{draftId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID draftId) {
		this.drafts.delete(this.playerRefs.currentPlayerRef(), draftId);
	}
}

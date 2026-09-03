package com.neowadaeum.authoring.api;

import com.neowadaeum.authoring.review.StoryDeletionService;
import com.neowadaeum.common.web.PlayerRefResolver;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 작품 삭제 (§13-58, #290-3).
 *
 * <p><b>{@code GET} 과 같은 경로이고 다른 모듈이다.</b> 상세 조회는 {@code play} 가 답하고
 * (읽는 사람의 화면이다) 삭제는 {@code authoring} 이 받는다 — 작성자의 처분이기 때문이다.
 * 한 컨트롤러에 합치면 모듈 경계가 경로 모양을 따라 무너진다 (§5.4).
 *
 * <p><b>{@code StoryVisibilityController} 와 나눈 것도 의도다.</b> 그쪽은 {@code /visibility}
 * 하위 경로만 맡으며, 이름이 맡는 일과 같아야 다음 사람이 찾을 자리를 안다.
 *
 * <p><b>본문이 없다</b> — {@code 204}. 답할 상태가 없기 때문이다: 지운 작품은 이 API 로 다시
 * 조회되지 않는다 (§13-58 — 되돌리는 경로를 만들지 않는다).
 */
@RestController
public class StoryDeletionController {

	private final StoryDeletionService deletion;

	private final PlayerRefResolver playerRefs;

	public StoryDeletionController(StoryDeletionService deletion, PlayerRefResolver playerRefs) {
		this.deletion = deletion;
		this.playerRefs = playerRefs;
	}

	/**
	 * <b>없어도 성공으로 두지 않는다</b> — 원고 삭제({@code DELETE /authoring/drafts/{draftId}})와
	 * 다른 판단이다. 원고 id 는 작성자 밖으로 나가지 않지만 {@code storyId} 는 <b>누구나 들고
	 * 있다</b>. 항상 {@code 204} 로 답하면 남의 작품에 대고 부른 요청과 자기 작품을 지운 요청이
	 * 같은 답을 받아, 화면이 <b>지워지지 않은 것을 지워졌다고</b> 말하게 된다.
	 */
	@DeleteMapping("/api/v1/stories/{storyId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID storyId) {
		this.deletion.delete(this.playerRefs.currentPlayerRef(), storyId);
	}
}

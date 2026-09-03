package com.neowadaeum.authoring.api;

import com.neowadaeum.authoring.review.StoryAppealService;
import com.neowadaeum.common.web.PlayerRefResolver;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 재검토 요청 (#290, §13-59, R8.9).
 *
 * <p><b>화면이 계약보다 앞서 있던 자리다</b> (#290). 정지 화면은 "이의가 있으면 문의해 주세요"와
 * [이의 제기] 버튼을 그렸는데 <b>갈 곳이 없었다</b> — 문의하라고 적어 놓고 문의할 곳이 없으면
 * 안내가 아니라 방치다.
 *
 * <p><b>{@code 202} 다.</b> 접수했다는 뜻이지 복구한다는 뜻이 아니다. 판정은 사람이 하고
 * (R8.9 — 자동으로 내린 것을 자동으로 올리지 않는다), 이 요청이 바꾸는 것은 검수자가 보는
 * 신호와 그 사실의 기록뿐이다.
 *
 * <p><b>본문을 되돌려주지 않는다.</b> 되돌려줄 것이 없다 — 작품의 상태는 그대로
 * {@code suspended} 이고, 그 값은 {@code GET /api/v1/me/stories} 가 이미 준다.
 */
@RestController
@RequestMapping("/api/v1/stories/{storyId}")
public class StoryAppealController {

	private final StoryAppealService appeals;

	private final PlayerRefResolver playerRefs;

	public StoryAppealController(StoryAppealService appeals, PlayerRefResolver playerRefs) {
		this.appeals = appeals;
		this.playerRefs = playerRefs;
	}

	/** 판정은 서비스가 한다 — <b>남의 작품은 없는 것과 구분되지 않는다</b> (I-8). */
	@PostMapping("/appeal")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public void appeal(@PathVariable UUID storyId, @Valid @RequestBody AppealRequest body) {
		this.appeals.appeal(this.playerRefs.currentPlayerRef(), storyId, body.reason());
	}
}

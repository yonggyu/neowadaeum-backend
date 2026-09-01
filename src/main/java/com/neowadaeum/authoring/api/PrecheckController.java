package com.neowadaeum.authoring.api;

import com.neowadaeum.authoring.draft.DraftService;
import com.neowadaeum.authoring.precheck.PrecheckScreen;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.common.support.RateLimitProperties;
import com.neowadaeum.common.support.RateLimiter;
import com.neowadaeum.common.web.PlayerRefResolver;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 입력 중 검수 (§13.8, B-50).
 *
 * <p><b>제출 후 반려가 아니라 입력 중 피드백이다</b> (§8.2). 30분 걸려 쓴 작품이 제출 직후
 * 반려되면 사용자는 이탈한다.
 *
 * <p><b>결과를 원고에 기록한다.</b> 그러지 않으면 다음 단계 게이트(R8.3)가 볼 것이 없다 —
 * 응답만 돌려주면 <b>클라이언트 검증에만 의존</b>하게 되고, 그것이 R8.3 이 막으려는 상태다.
 *
 * <p><b>분당 20회 제한</b> (R8.4). 입력 중에 부르는 경로이므로 debounce 가 무너지면 타자마다
 * 요청이 간다.
 */
@RestController
@RequestMapping("/api/v1/authoring/drafts/{draftId}/precheck")
public class PrecheckController {

	private final PrecheckScreen screen;

	private final DraftService drafts;

	private final RateLimiter rateLimiter;

	private final RateLimitProperties limits;

	private final PlayerRefResolver playerRefs;

	public PrecheckController(PrecheckScreen screen, DraftService drafts, RateLimiter rateLimiter,
			RateLimitProperties limits, PlayerRefResolver playerRefs) {
		this.screen = screen;
		this.drafts = drafts;
		this.rateLimiter = rateLimiter;
		this.limits = limits;
		this.playerRefs = playerRefs;
	}

	@PostMapping
	public PrecheckResponse precheck(@PathVariable UUID draftId,
			@Valid @RequestBody PrecheckRequest body) {
		UUID authorRef = this.playerRefs.currentPlayerRef();
		requireWithinLimit(authorRef);
		// 남의 원고에는 부를 수 없다 (I-8). 없는 것과 구분되지 않는다.
		this.drafts.read(authorRef, draftId);

		PrecheckScreen.Result result = this.screen.screen(body.fields());
		this.drafts.recordPrecheck(authorRef, draftId, result);
		return new PrecheckResponse(result.state().columnValue(), result.findings());
	}

	/**
	 * <b>계정 기준이다</b> (R8.4).
	 *
	 * <p>인증된 경로이므로 IP 로 셀 이유가 없고, IP 로 세면 같은 회선의 다른 작성자가 함께
	 * 막힌다.
	 */
	private void requireWithinLimit(UUID authorRef) {
		if (!this.rateLimiter.tryAcquire("precheck", authorRef.toString(),
				this.limits.precheckPerMinute(), RateLimitProperties.MINUTE)) {
			throw new ApiException(ErrorCode.RATE_LIMITED,
					Map.of("retryAfterSeconds", RateLimitProperties.MINUTE.toSeconds()));
		}
	}
}

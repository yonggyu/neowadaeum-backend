package com.neowadaeum.authoring.api;

import com.neowadaeum.authoring.review.SubmissionService;
import com.neowadaeum.common.web.PlayerRefResolver;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 제출과 검수 상태 (§13.8, B-54).
 *
 * <p><b>{@code 202} 다.</b> 자동 검수는 즉시 끝나지만 {@code public} 은 사람을 기다린다 (R8.6) —
 * 두 경우를 다른 상태 코드로 나누면 클라이언트가 <b>둘을 다르게 다루게</b> 되고, 그러면
 * "승인됐다"와 "접수됐다"를 화면이 헷갈린다.
 *
 * <p><b>남의 원고는 제출할 수 없다</b> (I-8). 판정은 서비스가 한다.
 */
@RestController
@RequestMapping("/api/v1/authoring/drafts/{draftId}")
public class SubmissionController {

	private final SubmissionService submissions;

	private final PlayerRefResolver playerRefs;

	private final Clock clock;

	public SubmissionController(SubmissionService submissions, PlayerRefResolver playerRefs,
			Clock clock) {
		this.submissions = submissions;
		this.playerRefs = playerRefs;
		this.clock = clock;
	}

	@PostMapping("/submit")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public ReviewStatusResponse submit(@PathVariable UUID draftId,
			@Valid @RequestBody SubmitRequest body) {
		SubmissionService.SubmissionOutcome outcome = this.submissions
				.submit(this.playerRefs.currentPlayerRef(), draftId, body.visibility());
		return ReviewStatusResponse.of(outcome, Instant.now(this.clock));
	}

	/**
	 * 검수 상태를 다시 본다.
	 *
	 * <p><b>제출한 적이 없으면 {@code draft} 다</b> — 없는 것이 아니라 아직 내지 않은 것이며,
	 * 404 로 답하면 작성자는 원고가 사라졌다고 읽는다.
	 */
	@GetMapping("/review")
	public ReviewStatusResponse review(@PathVariable UUID draftId) {
		SubmissionService.SubmissionOutcome outcome = this.submissions
				.reviewStatus(this.playerRefs.currentPlayerRef(), draftId);
		return ReviewStatusResponse.of(outcome, Instant.now(this.clock));
	}
}

package com.neowadaeum.admin;

import com.neowadaeum.authoring.review.ReviewQueueService;
import com.neowadaeum.common.web.PlayerRefResolver;
import com.neowadaeum.identity.access.AdminAccessGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인간 검수 큐 (§14, R8.6, B-55).
 *
 * <p><b>{@code public} 은 여기서 열린다</b> (R8.6). 자동 검수는 통과시켜도 열지 않으므로
 * (B-54), 이 문이 없으면 {@code public} 작품은 영원히 대기 상태에 머문다.
 *
 * <p><b>세 조건은 보는 문에도 서 있다</b> (S-4). 큐에 걸린 것은 <b>아직 아무도 보지 못한
 * 작품</b>이며, 그 목록이 새면 검수 전 UGC 가 새는 것과 같다 (I-8).
 *
 * <p><b>판정은 감사에 남는다</b> (R14.5). 누가 무엇을 열었는지 모르는 승인은 사후에 되짚을 수
 * 없다 — 남기는 것은 <b>판정과 대상</b>까지이고, 사유 카테고리는 검수 이력이 갖는다.
 */
@RestController
@RequestMapping("/api/v1/admin/reviews")
public class AdminReviewController {

	private final ReviewQueueService queue;

	private final AdminAccessGuard guard;

	private final PlayerRefResolver playerRefs;

	public AdminReviewController(ReviewQueueService queue, AdminAccessGuard guard,
			PlayerRefResolver playerRefs) {
		this.queue = queue;
		this.guard = guard;
		this.playerRefs = playerRefs;
	}

	/** 지금 볼 차례인 것들. 오래 기다린 것부터 온다. */
	@GetMapping
	public List<ReviewQueueItemResponse> pending(HttpServletRequest request) {
		this.guard.requireAdmin(this.playerRefs.currentPlayerRef(), request);

		return this.queue.pending().stream().map(ReviewQueueItemResponse::of).toList();
	}

	/**
	 * 판정한다.
	 *
	 * <p><b>검수자는 {@code player_ref} 로 남는다</b> (§5.3) — catalog 는 Identity 스토어가
	 * 아니므로 {@code user.id} 를 담지 않는다. 감사 로그가 사람을 가리키는 것은 별개의 표다.
	 */
	@PostMapping("/{storyId}/verdict")
	public ReviewVerdictResponse decide(@PathVariable UUID storyId,
			@Valid @RequestBody ReviewVerdictRequest body, HttpServletRequest request) {
		UUID reviewerPlayerRef = this.playerRefs.currentPlayerRef();
		UUID adminUserId = this.guard.requireAdmin(reviewerPlayerRef, request);

		ReviewQueueService.Decision decision = this.queue.decide(reviewerPlayerRef, storyId,
				body.verdict(), body.reasons(), body.note());
		this.guard.recordAction(adminUserId, "admin.review.verdict", "story", storyId,
				Map.of("verdict", body.verdict().columnValue()), request);
		return new ReviewVerdictResponse(decision.storyId(), decision.reviewStatus().columnValue());
	}

	/**
	 * 판정 뒤의 상태.
	 *
	 * <p><b>{@code hold} 는 대기 상태 그대로 돌아온다</b> — 판정했지만 아무것도 바뀌지 않았다는
	 * 사실이 응답에 그대로 보여야, 검수자가 자기가 무엇을 했는지 안다.
	 */
	public record ReviewVerdictResponse(UUID storyId, String reviewStatus) {
	}
}

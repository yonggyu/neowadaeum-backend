package com.neowadaeum.admin;

import com.neowadaeum.authoring.report.ReportInspection;

import com.neowadaeum.authoring.review.ReviewManuscript;
import com.neowadaeum.authoring.review.ReviewManuscriptService;
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

	private final ReportInspection inspection;

	private final ReviewManuscriptService manuscripts;

	private final AdminAccessGuard guard;

	private final PlayerRefResolver playerRefs;

	public AdminReviewController(ReviewQueueService queue, ReportInspection inspection,
			ReviewManuscriptService manuscripts, AdminAccessGuard guard,
			PlayerRefResolver playerRefs) {
		this.queue = queue;
		this.inspection = inspection;
		this.manuscripts = manuscripts;
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
	 * 이 작품에 무엇이 신고됐는가 (§13-62, 이슈 #316).
	 *
	 * <p><b>큐가 아니라 큐 안의 내용이다.</b> 임계에 닿은 작품은 이미 같은 검수 큐에
	 * {@code suspended} 로 올라와 있으며 (§13-41), 없던 것은 <b>왜 올라왔는지</b>였다 —
	 * 사유와 건수를 모르면 검수자는 제목과 상태만 보고 판정하게 된다.
	 *
	 * <p><b>읽는 것도 남긴다</b> (R14.5). 신고는 이용자가 쓴 것이고, 누가 언제 그것을 열었는지는
	 * 사후에 되짚을 수 있어야 한다 — 큐 목록과 달리 이 문 뒤에는 <b>특정 작품에 대해 사람들이
	 * 무엇을 문제 삼았는지</b>가 있다.
	 */
	@GetMapping("/{storyId}/reports")
	public StoryReportsResponse reports(@PathVariable UUID storyId, HttpServletRequest request) {
		UUID adminUserId = this.guard.requireAdmin(this.playerRefs.currentPlayerRef(), request);

		StoryReportsResponse response = StoryReportsResponse.of(this.inspection.forStory(storyId));
		this.guard.recordAction(adminUserId, "admin.review.reports.read", "story", storyId,
				Map.of("reportCount", response.total()), request);
		return response;
	}

	/**
	 * 원고를 연다 (#316, §13-61).
	 *
	 * <p><b>큐가 답하지 않는 것이 여기 있다.</b> 큐는 제목과 상태만 주고 원고 본문을 담지 않으며,
	 * 그것만 보고 누르는 승인은 검수가 아니다 — 이 문이 <b>무엇을 보고 판정하는가</b>를 답한다.
	 *
	 * <p><b>열람은 두 번 남는다.</b> 관리자 행위로 한 번 (R14.5), <b>원문 열람</b>으로 한 번
	 * (R12.3, S-5) — 후자는 서비스가 남기고 <b>기록에 실패하면 원문이 나가지 않는다.</b> 여기서
	 * 둘 다 남기면 기록을 잊은 호출자가 생긴다.
	 */
	@GetMapping("/{storyId}")
	public ReviewManuscript manuscript(@PathVariable UUID storyId, HttpServletRequest request) {
		UUID adminUserId = this.guard.requireAdmin(this.playerRefs.currentPlayerRef(), request);

		ReviewManuscript manuscript = this.manuscripts.read(adminUserId, storyId);
		this.guard.recordAction(adminUserId, "admin.review.manuscript", "story", storyId, Map.of(),
				request);
		return manuscript;
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

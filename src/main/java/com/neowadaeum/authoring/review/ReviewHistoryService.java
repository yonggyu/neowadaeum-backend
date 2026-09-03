package com.neowadaeum.authoring.review;

import com.neowadaeum.catalog.publish.StoryPublisher;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 작품 하나의 <b>지난 판정</b> (§13-63, #316).
 *
 * <p><b>검수 이력은 append-only 다</b> (I-5) — 같은 작품에 판정이 여러 번 쌓이고
 * ({@link SubmissionService 제출·재제출} · {@link StoryVisibilityService 승격} ·
 * {@link UgcRescanner 재스캔} · {@link UgcReviewSampler 샘플링} · 사람 판정) 마지막 하나만
 * 보면 <b>왜 그렇게 됐는지</b>를 잃는다. 검수자가 지금 보는 작품이 <b>전에 무엇으로 걸렸던
 * 작품인지</b>는 판정에 직접 쓰인다.
 *
 * <p><b>{@link StoryReviewTimeline} 과 합치지 않는다.</b> 그쪽은 같은 표를 읽지만 하는 일이
 * 다르다 — <b>회차를 갈라 두 시각을 뽑는</b> 일이고, 그래서 회차보다 앞선 기록을 <b>일부러
 * 버린다.</b> 이력은 버리는 것이 없어야 하는 자리이므로, 한 클래스로 묶으면 한쪽의 규칙이
 * 다른 쪽에서 결함이 된다.
 *
 * <p><b>{@code reviewer_ref} 를 밖으로 내보내지 않는다</b> (I-3, §5.3). 그 값은
 * {@code player_ref} 이며, 누가 판정했는지를 답하는 자리는 관리자 감사 기록이다 — 검수자를
 * 응답에 실으면 <b>관리자 화면 하나가 회원 식별자를 나르는 통로</b>가 된다. 이 클래스가
 * 돌려주는 {@link Entry} 에 그 자리를 두지 않은 것이 그 구조적 보장이다.
 *
 * <p><b>{@code note} 는 담는다.</b> 검수자가 문장을 적는 유일한 자리이고, 이 경로는 관리자
 * 전용이다 — 작성자가 보는 {@link com.neowadaeum.authoring.api.ReviewStatusResponse} 는
 * 카테고리만 싣는다 (R8.7, S-11).
 */
@Service
public class ReviewHistoryService {

	private static final tools.jackson.databind.json.JsonMapper JSON =
			tools.jackson.databind.json.JsonMapper.builder().build();

	private final StoryPublisher publisher;

	private final StoryReviewRepository reviews;

	public ReviewHistoryService(StoryPublisher publisher, StoryReviewRepository reviews) {
		this.publisher = publisher;
		this.reviews = reviews;
	}

	/**
	 * 최근 판정부터.
	 *
	 * <p><b>작품이 없으면 {@code 404} 이고, 판정이 없으면 빈 목록이다.</b> 둘은 다른 사실이다 —
	 * 아직 아무도 판정하지 않은 작품(미리보기로만 만들어진 원고, §13-5)은 존재하며, 그것을
	 * {@code 404} 로 답하면 화면이 <b>없는 작품</b>이라고 적는다.
	 *
	 * <p><b>쪽을 나누지 않는다</b> (§13-63). 한 작품의 기록은 제출 회차 수를 따라가므로 몇 건
	 * 수준이고, 커서를 두면 <b>화면이 쓰지 않는 상태</b>가 계약에 들어간다. 늘어나는 것이
	 * 관측되면 그때 커서를 더한다 — 지금 넣으면 근거 없는 복잡도다.
	 */
	public List<Entry> of(UUID storyId) {
		// §13-58 — 지워진 작품은 없는 작품이다. statusOf 가 이미 그 행을 보지 못한다.
		if (this.publisher.statusOf(storyId).isEmpty()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return this.reviews.findByStoryIdOrderByReviewedAtDesc(storyId).stream()
				.map(ReviewHistoryService::entryOf).toList();
	}

	private static Entry entryOf(StoryReview review) {
		return new Entry(review.getStage(), review.getVerdict(), reasonsOf(review.getReasons()),
				review.getReviewedAt(), review.getNote());
	}

	/** 저장된 것은 카테고리 배열이다 (R8.7). 파싱기를 들이지 않고 그대로 읽는다. */
	private static List<String> reasonsOf(String reasonsJson) {
		List<String> reasons = new ArrayList<>();
		for (var node : JSON.readTree(reasonsJson)) {
			reasons.add(node.asString());
		}
		return List.copyOf(reasons);
	}

	/**
	 * 판정 한 건.
	 *
	 * <p><b>검수자를 담지 않는다</b> (I-3) — 위 참조.
	 *
	 * @param stage 자동인지 사람인지. <b>둘을 섞지 않는다</b> — 자동 통과는 사람이 본 것이
	 * 아니며, 그 구분이 없으면 화면은 <b>아무도 보지 않은 작품을 승인된 작품으로</b> 적는다
	 * @param reasons 사유 <b>카테고리만</b>이다 (R8.7, S-11). 어떤 항목에 걸렸는지를 담으면
	 * 이 응답이 우회 사전이 된다
	 * @param note 검수자의 내부 기록. 없으면 {@code null} 이며, 자동 판정에는 사람이 없어 늘
	 * 비어 있다
	 */
	public record Entry(ReviewStage stage, ReviewVerdict verdict, List<String> reasons,
			Instant reviewedAt, String note) {
	}
}

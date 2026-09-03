package com.neowadaeum.authoring.review;

import com.neowadaeum.common.spi.StoryReviewTimes;
import com.neowadaeum.common.spi.StoryReviewTimesQuery;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 검수 이력에서 <b>신청 시각과 승인 시각</b>을 읽는다 (§13-57, #290).
 *
 * <p><b>회차를 가르는 것이 이 클래스의 전부다.</b> 같은 작품에 검수가 여러 번 일어나므로
 * (재제출 · 승격 · 신고 · 재스캔) 이력만으로는 <b>어느 신청에 대한 어느 판정인지</b>가
 * 갈리지 않는다. 화면이 <b>"2월 21일 신청 · 보통 1~3일"</b> 이라고 적으려면 그 둘이 같은
 * 회차의 것이어야 한다 — 지난 회차의 승인 시각을 이번 신청 옆에 두면 그것은 안내가 아니다.
 *
 * <h2>회차의 시작은 {@code auto} + {@code pass} 기록이다</h2>
 *
 * <p><b>지어낸 정의가 아니라 코드에 이미 있는 사실이다.</b> 그 기록을 남기는 곳은 둘뿐이고
 * 둘 다 <b>검수를 요청하는 행위</b>다 — 제출·재제출({@link SubmissionService})과
 * 승격({@link StoryVisibilityService#change}). 나머지 자동 기록은 요청이 아니다:
 * 재스캔은 {@code auto} + {@code reject}({@link UgcRescanner}), 샘플링은 {@code auto} +
 * {@code hold}({@link UgcReviewSampler}) 이며 <b>작성자가 부른 적이 없다.</b>
 *
 * <p>그래서 <b>가장 최근 {@code auto} + {@code pass}</b> 가 지금 회차의 시작이고, 그것이
 * {@code submittedAt} 이다. 없으면 이 작품은 검수를 요청한 적이 없다 — 미리보기로만 만들어진
 * 작품이 그렇다 (§13-5).
 *
 * <h2>승인 시각은 그 뒤의 마지막 사람 판정이다</h2>
 *
 * <p><b>회차 안으로 한정한다.</b> 지난 회차의 승인은 이번 신청에 대한 답이 아니므로,
 * 재제출 직후에는 {@code reviewedAt} 이 {@code null} 로 돌아간다 — <b>아직 답이 없다</b>는
 * 것이 사실이기 때문이다.
 *
 * <p><b>사람이 없으면 {@code null} 이다.</b> {@code unlisted} 제출은 자동 검수만으로 열리므로
 * (R8.6) 승인 시각이 영영 없다. 자동 통과 시각을 승인 시각이라고 적으면 화면은 <b>사람이 본
 * 작품과 보지 않은 작품을 구분하지 못한다.</b>
 */
@Component
public class StoryReviewTimeline implements StoryReviewTimesQuery {

	private final StoryReviewRepository reviews;

	public StoryReviewTimeline(StoryReviewRepository reviews) {
		this.reviews = reviews;
	}

	/** 작품 하나. 검수 이력이 없으면 {@link StoryReviewTimes#NONE} 이다. */
	public StoryReviewTimes of(UUID storyId) {
		return foldDescending(this.reviews.findByStoryIdOrderByReviewedAtDesc(storyId));
	}

	/**
	 * 목록 하나를 조회 <b>한 번</b>으로 채운다 (§15).
	 *
	 * <p>비어 있으면 묻지 않는다 — {@code IN ()} 은 질의가 아니다.
	 */
	@Override
	public Map<UUID, StoryReviewTimes> findByStoryIds(Collection<UUID> storyIds) {
		if (storyIds == null || storyIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, List<StoryReview>> byStory = new LinkedHashMap<>();
		for (StoryReview review : this.reviews.findByStoryIdInOrderByReviewedAtDesc(storyIds)) {
			byStory.computeIfAbsent(review.getStoryId(), key -> new java.util.ArrayList<>())
					.add(review);
		}
		Map<UUID, StoryReviewTimes> times = new HashMap<>(byStory.size());
		byStory.forEach((storyId, history) -> times.put(storyId, foldDescending(history)));
		return Map.copyOf(times);
	}

	/**
	 * 최신부터 거슬러 올라가며 회차를 찾는다.
	 *
	 * <p>가장 최근 {@code auto} + {@code pass} 에서 멈춘다 — 그 앞의 것은 <b>지난 회차</b>이고,
	 * 그 사이에서 본 마지막 사람 판정이 이번 회차의 답이다.
	 */
	private static StoryReviewTimes foldDescending(List<StoryReview> descending) {
		Instant lastHuman = null;
		for (StoryReview review : descending) {
			if (review.getStage() == ReviewStage.AUTO && review.getVerdict() == ReviewVerdict.PASS) {
				return new StoryReviewTimes(review.getReviewedAt(), lastHuman);
			}
			if (review.getStage() == ReviewStage.HUMAN && lastHuman == null) {
				lastHuman = review.getReviewedAt();
			}
		}
		// 회차가 없으면 판정도 이번 것이 아니다. 신청 없는 승인 시각을 남기지 않는다.
		return StoryReviewTimes.NONE;
	}

}

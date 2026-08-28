package com.neowadaeum.authoring.review;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 검수 이력 (§2.4). catalog 스토어에 있고 소유는 authoring 이다 (ADR-0002). */
public interface StoryReviewRepository extends JpaRepository<StoryReview, UUID> {

	/** 가장 최근 판정. 화면이 보여 주는 것은 이것이다. */
	Optional<StoryReview> findFirstByStoryIdOrderByReviewedAtDesc(UUID storyId);

	/** 이력 전체. <b>왜 그렇게 됐는지</b>는 여기에 있다. */
	List<StoryReview> findByStoryIdOrderByReviewedAtDesc(UUID storyId);

	/**
	 * 여러 작품의 이력을 한 번에 (B-55).
	 *
	 * <p>큐는 작품마다 <b>언제부터 기다렸는지</b>를 보여 준다. 작품 수만큼 조회하면 큐 한 장을
	 * 여는 데 조회가 그만큼 늘어난다.
	 */
	List<StoryReview> findByStoryIdInOrderByReviewedAtDesc(Collection<UUID> storyIds);

	/**
	 * <b>사람 눈에 올려 두었지만 아직 아무도 보지 않은 작품</b> (R8.11, B-59, §13-42).
	 *
	 * <p>샘플링은 작품 상태를 바꾸지 않으므로 (<b>내리면 안 된다</b> — §13-12) 큐에 있다는
	 * 사실이 검수 이력에만 남는다. 표식은 <b>자동 단계의 보류</b>다: 봐야 하는데 아직 안 봤다.
	 *
	 * <p><b>해소 경로가 따로 없다.</b> 사람이 판정하면 인간 이력이 얹혀 표식이 더 이상 최신이
	 * 아니게 된다 — 지우는 일이 없으니 지우다 실패하는 일도 없다.
	 */
	@org.springframework.data.jpa.repository.Query("""
			SELECT r.storyId FROM StoryReview r
			WHERE r.stage = 'auto' AND r.verdict = 'hold'
			  AND r.reviewedAt = (
			      SELECT MAX(latest.reviewedAt) FROM StoryReview latest
			      WHERE latest.storyId = r.storyId)
			""")
	List<UUID> storyIdsFlaggedForReview(org.springframework.data.domain.Limit limit);

	/** 이 작품이 지금 표식을 달고 있는가. 두 번 올리지 않기 위해 본다. */
	default boolean isFlaggedForReview(UUID storyId) {
		return findFirstByStoryIdOrderByReviewedAtDesc(storyId)
				.filter(review -> review.getStage() == ReviewStage.AUTO
						&& review.getVerdict() == ReviewVerdict.HOLD)
				.isPresent();
	}
}

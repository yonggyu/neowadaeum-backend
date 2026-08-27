package com.neowadaeum.authoring.review;

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
}

package com.neowadaeum.authoring.review;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** 재검토 요청 (#290, §13-59). catalog 스토어에 있고 소유는 authoring 이다 (ADR-0002). */
public interface StoryAppealRepository extends JpaRepository<StoryAppeal, UUID> {

	/**
	 * <b>아직 사람이 답하지 않은 요청이 걸린 작품들</b> (§13-59).
	 *
	 * <p><b>이것이 "정지 건마다 한 번"의 정의다.</b> 정지 사건에는 id 가 없다 — 정지는
	 * {@code review_status} 를 바꾸는 일이지 행을 만드는 일이 아니다 (§13-41). 대신 <b>인간
	 * 판정이 그 사건을 닫는다</b>: 통과하면 작품이 돌아오고 반려하면 내려간 채로 끝나므로,
	 * 요청 뒤에 사람 판정이 얹혔다면 그 요청은 <b>답을 받은</b> 요청이다.
	 *
	 * <p>그래서 다시 정지되면 이의도 다시 제기할 수 있고, 답을 받기 전에는 두 번 낼 수 없다 —
	 * <b>같은 규칙 하나가 둘을 함께 말한다.</b> 작품마다 한 번으로 정하면 한 번 복구된 작품은
	 * 두 번째 자동 정지에 아무 말도 못 하게 된다.
	 *
	 * <p><b>자동 이력은 세지 않는다.</b> 재스캔·샘플링은 사람이 답한 것이 아니다 (§13-42).
	 */
	@Query("""
			SELECT DISTINCT a.storyId FROM StoryAppeal a
			WHERE a.storyId IN :storyIds
			  AND NOT EXISTS (
			      SELECT answer.id FROM StoryReview answer
			      WHERE answer.storyId = a.storyId AND answer.stage = 'human'
			        AND answer.reviewedAt > a.createdAt)
			""")
	List<UUID> storyIdsWithOpenAppeal(Collection<UUID> storyIds);

	/**
	 * 이 작품에 답을 기다리는 요청이 있는가.
	 *
	 * <p>큐가 쓰는 것과 <b>같은 질의</b>다. 나누면 두 규칙이 되고, 검수자가 보는 신호와 두 번째
	 * 요청을 막는 판정이 서로 다른 답을 낼 수 있다.
	 */
	default boolean hasOpenAppeal(UUID storyId) {
		return !storyIdsWithOpenAppeal(List.of(storyId)).isEmpty();
	}
}

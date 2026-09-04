package com.neowadaeum.authoring.draft;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 원고 영속화 (§2.4). catalog 스토어에 있고 소유는 authoring 이다 (ADR-0002). */
public interface StoryDraftRepository extends JpaRepository<StoryDraft, UUID> {

	/** 내 원고 목록. 최근 것부터. */
	List<StoryDraft> findByAuthorRefOrderByUpdatedAtDesc(UUID authorRef);

	/**
	 * 발행된 작품에서 원고를 되찾는다 (#340).
	 *
	 * <p>순서를 박아 두는 것은 <b>한 작품에 원고가 둘일 때 무엇이 나오는지</b>를 조회가
	 * 스스로 말하게 하기 위해서다 ({@link StoryDraftLinks}).
	 */
	List<StoryDraft> findByStoryIdInOrderByUpdatedAtDesc(Collection<UUID> storyIds);

	/**
	 * 파기 후보 중 원고가 아직 가리키고 있는 미리보기 (#332).
	 *
	 * <p>후보만 받으므로 대개 빈 결과다 — 30일을 넘긴 미리보기가 검수 중인 경우는 드물다.
	 */
	List<StoryDraft> findByPreviewStoryIdIn(Collection<UUID> previewStoryIds);

	/** 이 작품을 발행한 원고 (#332). 검수 상세가 미리보기 세션을 찾는 길이다. */
	Optional<StoryDraft> findFirstByStoryIdOrderByUpdatedAtDesc(UUID storyId);

	/** 개수 상한 확인 (R8.12). 없으면 한 계정이 저장소를 채운다. */
	int countByAuthorRef(UUID authorRef);
}

package com.neowadaeum.authoring.draft;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 원고 영속화 (§2.4). catalog 스토어에 있고 소유는 authoring 이다 (ADR-0002). */
public interface StoryDraftRepository extends JpaRepository<StoryDraft, UUID> {

	/** 내 원고 목록. 최근 것부터. */
	List<StoryDraft> findByAuthorRefOrderByUpdatedAtDesc(UUID authorRef);

	/** 개수 상한 확인 (R8.12). 없으면 한 계정이 저장소를 채운다. */
	int countByAuthorRef(UUID authorRef);
}

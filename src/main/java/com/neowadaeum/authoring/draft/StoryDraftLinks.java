package com.neowadaeum.authoring.draft;

import com.neowadaeum.common.spi.StoryDraftLinkQuery;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 발행된 작품에서 원고로 되돌아가는 길 (#340).
 *
 * <p><b>연결은 제출이 만든다.</b> {@code SubmissionService} 가 발행 직후
 * {@code DraftService.linkStory} 로 {@code story_draft.story_id} 를 채우며, 재제출은 같은
 * 작품에 새 버전을 얹으므로 그 값이 바뀌지 않는다. 미리보기가 발행하는 임시 작품은
 * 연결하지 않는다 (§13-5) — 그래서 <b>원고 없는 작품이 실제로 존재</b>하고, 이 조회는
 * 그런 작품의 키를 아예 돌려주지 않는다.
 *
 * <p><b>한 작품에 원고가 둘이면 최근 것을 준다.</b> 지금 그 상태를 만드는 경로는 없지만
 * (제출은 원고 하나가 작품 하나를 낳는다), 조회가 <b>어느 것이 나올지 모르는 채</b>
 * 남으면 화면은 같은 목록을 두 번 열 때 다른 원고로 간다.
 */
@Component
public class StoryDraftLinks implements StoryDraftLinkQuery {

	private final StoryDraftRepository drafts;

	public StoryDraftLinks(StoryDraftRepository drafts) {
		this.drafts = drafts;
	}

	/** 비어 있으면 묻지 않는다 — {@code IN ()} 은 질의가 아니다. */
	@Override
	public Map<UUID, UUID> findDraftIdsByStoryIds(Collection<UUID> storyIds) {
		if (storyIds == null || storyIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, UUID> byStory = new HashMap<>();
		// 최근 것부터 오므로 먼저 들어온 것이 남는다.
		for (StoryDraft draft : this.drafts.findByStoryIdInOrderByUpdatedAtDesc(storyIds)) {
			byStory.putIfAbsent(draft.getStoryId(), draft.getId());
		}
		return Map.copyOf(byStory);
	}

}

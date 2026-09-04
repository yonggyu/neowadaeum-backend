package com.neowadaeum.authoring.draft;

import com.neowadaeum.catalog.publish.StoryPublisher;
import com.neowadaeum.common.spi.PreviewRetentionHold;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 검수가 끝날 때까지 미리보기를 살려 둔다 (§13-68, #332).
 *
 * <p><b>파기 후보만 받는다.</b> 보관 기간이 지나지 않은 것은 애초에 후보가 아니므로 이 조회는
 * 대개 빈 목록을 받고 빈 집합을 돌려준다 — 30일을 넘긴 미리보기가 검수 중인 경우는 드물다.
 *
 * <p><b>유예 조건은 두 가지가 함께다.</b> 원고가 그 미리보기를 가리키고 있어야 하고(마지막
 * 미리보기여야 하고), 그 원고가 낸 작품이 <b>지금 판정을 기다리고</b> 있어야 한다. 둘 중 하나만
 * 보면 검수와 무관한 미리보기가 영원히 남거나, 검수 중인데 지워진다.
 */
@Component
public class PreviewRetentionHolds implements PreviewRetentionHold {

	/** 아직 사람이 답하지 않은 상태. 이 동안 검수자가 미리보기 턴을 본다. */
	private static final Set<String> AWAITING_VERDICT = Set.of("pending", "in_review");

	private final StoryDraftRepository drafts;

	private final StoryPublisher publisher;

	public PreviewRetentionHolds(StoryDraftRepository drafts, StoryPublisher publisher) {
		this.drafts = drafts;
		this.publisher = publisher;
	}

	@Override
	public Set<UUID> heldPreviewStories(Collection<UUID> previewStoryIds) {
		if (previewStoryIds == null || previewStoryIds.isEmpty()) {
			return Set.of();
		}
		Set<UUID> held = new HashSet<>();
		for (StoryDraft draft : this.drafts.findByPreviewStoryIdIn(previewStoryIds)) {
			if (awaitingVerdict(draft.getStoryId())) {
				held.add(draft.getPreviewStoryId());
			}
		}
		return Set.copyOf(held);
	}

	/**
	 * <b>낸 적이 없으면 유예하지 않는다.</b> 검수자가 볼 일이 없는 미리보기이고, 작성자는
	 * 언제든 다시 부를 수 있다 — 그것이 30일을 정한 근거 그대로다 (§13-37).
	 */
	private boolean awaitingVerdict(UUID storyId) {
		return storyId != null && this.publisher.statusOf(storyId)
				.map(status -> AWAITING_VERDICT.contains(status.reviewStatus())).orElse(false);
	}

}

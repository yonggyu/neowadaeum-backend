package com.neowadaeum.common.spi;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * 지금 지우면 안 되는 미리보기 작품 (§13-37, §13-68, #332).
 *
 * <p><b>30일은 "원고가 남아 있으니 다시 부르면 된다" 를 근거로 정해졌다.</b> 그 근거는 미리보기가
 * 아무에게도 연결되지 않았을 때의 것이다 — 검수자가 그 턴을 본다면 <b>검수가 끝날 때까지는 살아
 * 있어야 한다.</b> 파기되고 나면 검수 상세는 조용히 빈 자리를 그리고, 그 침묵은 <i>이 작품은
 * 아무 문장도 내놓지 않았다</i> 로 읽힌다.
 *
 * <p><b>왜 SPI 인가.</b> 판정에 필요한 것은 {@code story_draft} 이고 그것을 소유하는 것은
 * {@code authoring} 이다. 파기를 도는 {@code batch} 는 파사드가 아니라 {@code common/spi} 를
 * 쓴다 (ADR-0003) — {@link PreviewStoryPurge} · {@link PreviewSessionPurge} 와 같은 자리다.
 *
 * <p><b>유예는 영구가 아니다.</b> 판정이 나면(승인·반려) 그 미리보기는 다시 파기 대상이 된다 —
 * 검수자가 볼 일이 끝났고, 작성자는 언제든 다시 부를 수 있다.
 */
public interface PreviewRetentionHold {

	/**
	 * @param previewStoryIds 보관 기간이 지나 파기 후보가 된 작품들
	 * @return 그중 <b>검수를 기다리는 원고가 가리키는</b> 것들. 비어 있으면 전부 지워도 된다
	 */
	Set<UUID> heldPreviewStories(Collection<UUID> previewStoryIds);

}

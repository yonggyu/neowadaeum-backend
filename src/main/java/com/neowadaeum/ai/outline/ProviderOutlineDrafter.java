package com.neowadaeum.ai.outline;

import com.neowadaeum.ai.provider.OutlineRequest;
import com.neowadaeum.ai.provider.OutlineResult;
import com.neowadaeum.ai.provider.StoryProvider;
import com.neowadaeum.common.spi.OutlineDraft;
import com.neowadaeum.common.spi.OutlineDraftFailedException;
import com.neowadaeum.common.spi.OutlineDraftRequest;
import com.neowadaeum.common.spi.OutlineDrafter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 초안 SPI 를 Provider 호출로 잇는다 (B-52).
 *
 * <p><b>번호는 여기서 붙인다.</b> Provider 가 돌려주는 {@link OutlineResult} 에는 번호를 담을
 * 자리가 없다 — 모델에게 번호를 매기게 하면 <b>빠지거나 겹친 번호</b>가 그대로 원고에 들어온다
 * (R7.14).
 *
 * <p><b>모자라면 모자란 대로 돌려준다.</b> 부족한 자리를 빈 문장으로 채우면 작성자는 그것을
 * <b>AI 가 제안한 것</b>으로 읽는다 — 없는 제안과 빈 제안은 다르다.
 *
 * <p><b>실패의 이름을 SPI 것으로 바꾼다</b> (#238). {@code authoring} 은 {@code ai} 도
 * {@code play :: port} 도 보지 않으므로(§5.4) Provider 쪽 예외를 잡을 수 없다. 여기서 바꾸지
 * 않으면 그 실패는 <b>경계를 넘어가 500</b>이 된다 — 모델이 형식을 못 맞춘 것은 서버의 버그가
 * 아니다.
 */
@Component
public class ProviderOutlineDrafter implements OutlineDrafter {

	private final StoryProvider provider;

	public ProviderOutlineDrafter(StoryProvider provider) {
		this.provider = provider;
	}

	@Override
	public OutlineDraft draft(OutlineDraftRequest request) {
		OutlineResult result;
		try {
			result = this.provider.draftOutline(new OutlineRequest(request.worldPrompt(),
					request.chapterCount(), request.endingCount()));
		}
		catch (RuntimeException ex) {
			// 원인은 로그와 ai_call_log 가 갖는다. 여기서 하는 일은 경계를 넘길 이름을 주는 것뿐이다.
			throw new OutlineDraftFailedException("outline draft failed", ex);
		}

		List<OutlineDraft.Chapter> chapters = new ArrayList<>();
		for (int index = 0; index < result.chapters().size(); index++) {
			OutlineResult.Chapter chapter = result.chapters().get(index);
			chapters.add(new OutlineDraft.Chapter(index + 1, chapter.title(), chapter.summarySeed()));
		}

		List<OutlineDraft.Ending> endings = new ArrayList<>();
		for (int index = 0; index < result.endings().size(); index++) {
			OutlineResult.Ending ending = result.endings().get(index);
			endings.add(new OutlineDraft.Ending(index + 1, ending.label(), ending.epilogueText()));
		}
		return new OutlineDraft(chapters, endings);
	}
}

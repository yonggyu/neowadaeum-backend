package com.neowadaeum.ai.outline;

import com.neowadaeum.ai.provider.OutlineRequest;
import com.neowadaeum.ai.provider.OutlineResult;
import com.neowadaeum.ai.provider.StoryProvider;
import com.neowadaeum.common.spi.OutlineDraft;
import com.neowadaeum.common.spi.OutlineDraftRequest;
import com.neowadaeum.common.spi.OutlineDrafter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 초안 SPI 를 Provider 호출로 잇는다 (B-52).
 *
 * <p><b>Provider 는 문장만 돌려준다.</b> 번호와 구조는 여기서 붙인다 — 모델에게 번호를 매기게
 * 하면 <b>빠지거나 겹친 번호</b>가 그대로 원고에 들어온다.
 *
 * <p><b>모자라면 모자란 대로 돌려준다.</b> 부족한 자리를 빈 문장으로 채우면 작성자는 그것을
 * <b>AI 가 제안한 것</b>으로 읽는다 — 없는 제안과 빈 제안은 다르다.
 *
 * <p><b>{@code label} 과 {@code title} 은 첫 줄이다.</b> 모델이 한 항목을 여러 줄로 쓰므로,
 * 목록에 보일 이름과 설명을 그렇게 나눈다 — 통째로 넣으면 목록이 문단이 된다.
 */
@Component
public class ProviderOutlineDrafter implements OutlineDrafter {

	private final StoryProvider provider;

	public ProviderOutlineDrafter(StoryProvider provider) {
		this.provider = provider;
	}

	@Override
	public OutlineDraft draft(OutlineDraftRequest request) {
		OutlineResult result = this.provider.draftOutline(new OutlineRequest(request.worldPrompt(),
				request.chapterCount(), request.endingCount()));

		List<OutlineDraft.Chapter> chapters = new ArrayList<>();
		for (int index = 0; index < result.chapterOutlines().size(); index++) {
			String outline = result.chapterOutlines().get(index);
			chapters.add(new OutlineDraft.Chapter(index + 1, firstLine(outline), outline));
		}

		List<OutlineDraft.Ending> endings = new ArrayList<>();
		for (int index = 0; index < result.endingOutlines().size(); index++) {
			String outline = result.endingOutlines().get(index);
			endings.add(new OutlineDraft.Ending(index + 1, firstLine(outline), rest(outline)));
		}
		return new OutlineDraft(chapters, endings);
	}

	private static String firstLine(String outline) {
		int newline = outline.indexOf('\n');
		return (newline < 0) ? outline.strip() : outline.substring(0, newline).strip();
	}

	/** 첫 줄 뒤가 없으면 {@code null} 이다 — 빈 문자열은 <b>비어 있는 글</b>로 읽힌다. */
	private static String rest(String outline) {
		int newline = outline.indexOf('\n');
		if (newline < 0) {
			return null;
		}
		String remainder = outline.substring(newline + 1).strip();
		return remainder.isEmpty() ? null : remainder;
	}
}

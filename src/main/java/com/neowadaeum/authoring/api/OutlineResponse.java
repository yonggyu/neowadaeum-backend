package com.neowadaeum.authoring.api;

import com.neowadaeum.common.spi.OutlineDraft;
import java.util.List;

/**
 * 챕터·엔딩 초안 (§13.8, R7.14).
 *
 * <p><b>조건 자리가 비어 있다</b> (R7.16). 작성자가 <b>템플릿에서 고르는</b> 값이며, AI 가
 * 지어낸 조건식이 들어오는 자리가 아니다 — 그래서 초안 응답의 {@code conditionTemplateKey} 는
 * 언제나 {@code null} 이다.
 *
 * <p><b>{@code isDefault} 도 여기서 정하지 않는다</b> (§13-16, I-10). 어느 것이 기본 엔딩인가는
 * 서버가 판정하며, 초안 단계에서는 아직 정해지지 않았다.
 */
public record OutlineResponse(List<Chapter> chapters, List<Ending> endings,
		List<String> conditionTemplates) {

	static OutlineResponse of(OutlineDraft draft, List<String> conditionTemplates) {
		return new OutlineResponse(
				draft.chapters().stream()
						.map(c -> new Chapter(c.chapterNo(), c.title(), c.summarySeed(), null)).toList(),
				draft.endings().stream()
						.map(e -> new Ending(e.endingNo(), e.label(), e.epilogueText(), false, null))
						.toList(),
				conditionTemplates);
	}

	public record Chapter(int chapterNo, String title, String summarySeed,
			String conditionTemplateKey) {
	}

	public record Ending(int endingNo, String label, String epilogueText, boolean isDefault,
			String conditionTemplateKey) {
	}
}

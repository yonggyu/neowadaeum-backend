package com.neowadaeum.ai.prompt;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 조립 결과 (B-20).
 *
 * <p><b>문자열 하나가 아니라 레이어 목록이다.</b> 어느 레이어가 얼마를 썼는지 남아 있어야 예산
 * 초과의 원인을 알 수 있고, 골든 파일도 레이어 경계가 보이는 형태여야 <b>프롬프트 변경이 diff 로</b>
 * 드러난다 ({@code .claude/rules/ai.md}).
 *
 * @param sections    레이어 순서대로. 내용이 없는 레이어는 아예 빠진다
 * @param totalTokens 전체 추정 토큰
 */
public record AssembledPrompt(List<Section> sections, int totalTokens) {

	public AssembledPrompt {
		sections = List.copyOf(sections == null ? List.of() : sections);
	}

	/**
	 * 골든 파일과 Provider 에 넘길 형태. 레이어 경계가 보이게 찍는다.
	 *
	 * <p><b>줄바꿈은 {@code \n} 으로 고정한다.</b> {@code System.lineSeparator()} 를 쓰면 같은 코드가
	 * 플랫폼마다 다른 프롬프트를 만들고, 골든 파일이 Windows 와 CI 에서 갈린다.
	 */
	public String render() {
		return this.sections.stream()
				.map(section -> "[%s]\n%s".formatted(section.layer().name(), section.text()))
				.collect(Collectors.joining("\n\n"));
	}

	/** 레이어 하나. */
	public record Section(PromptLayer layer, String text, int tokens) {
	}
}

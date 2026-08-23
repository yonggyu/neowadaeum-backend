package com.neowadaeum.play.api;

import java.util.List;
import java.util.UUID;

/**
 * 턴 응답 (§4.3-12, §5.2 출력 계약).
 *
 * <p><b>nullable 필드는 키를 생략하지 않고 {@code null} 로 명시한다</b> (web-api 규칙).
 * 프론트가 키 존재 여부로 분기하지 않게 한다.
 *
 * <p><b>{@code progressPercent} 를 만들지 않는다</b> (R7.5). AI 생성이라 챕터당 턴 수가 가변이므로
 * 62% 같은 숫자는 근거가 없다. {@code progressHint} 만 준다.
 *
 * @param turnNo        생성된 턴 번호. <b>요청값 + 1</b> 이다 (§4.3 턴 번호 계약)
 * @param chapterNo     판정 후 챕터
 * @param chapterTitle  전환 시 클라이언트가 인터스티셜에 쓴다 (R7.3)
 * @param chapterChanged 전환 여부 (R7.3)
 * @param progressHint  "Chapter 2 / 전체 3장" (R7.5)
 * @param speakerName   화자. 없으면 나레이션이다 (R5.2)
 * @param paragraphs    본문 문단 (R5.1 — 통 문자열 금지)
 * @param choices       서버가 발급한 선택지. 엔딩이면 빈 배열이다 (R7.8)
 * @param isEnding      종료 여부 (R7.8)
 * @param endingId      도달한 엔딩. 없으면 {@code null}
 * @param endingIndex   비시크릿 기준 순번. 시크릿이면 {@code null} (R7.11)
 * @param totalEndings  비시크릿 엔딩 수 (R7.11)
 */
public record TurnView(
		int turnNo,
		int chapterNo,
		String chapterTitle,
		boolean chapterChanged,
		String progressHint,
		String speakerName,
		List<String> paragraphs,
		List<Choice> choices,
		boolean isEnding,
		UUID endingId,
		Integer endingIndex,
		Integer totalEndings) {

	/**
	 * 선택지.
	 *
	 * <p><b>{@code choiceId} 는 서버가 발급한 값이다</b> (I-1). 다음 요청은 이것만 보내며,
	 * 클라이언트가 보낸 {@code text} 는 어떤 경우에도 신뢰하지 않는다.
	 *
	 * <p>{@code disabled} 는 서버가 판정한다 (I-11). P0 채택안대로 항상 {@code false} 이고
	 * {@code disabledReason} 은 {@code null} 이지만, <b>필드는 유지한다</b> — 프론트 계약
	 * 안정성 때문이다 (§13-3).
	 */
	public record Choice(String choiceId, int order, String text, boolean disabled, String disabledReason) {
	}
}

package com.neowadaeum.ai.provider;

import java.util.stream.Collectors;

/**
 * 요약 호출에 실을 본문 (R4.5, R4.7, B-34).
 *
 * <p><b>어댑터마다 따로 만들지 않는다.</b> 무엇을 압축 대상으로 넘기는가는 요구사항이 정한 것이지
 * 벤더 사정이 아니다 — 갈라지면 <b>같은 세션이 provider 에 따라 다른 것을 기억한다.</b> 벤더가
 * 다른 것은 이 문자열을 담는 봉투다.
 *
 * <p><b>I-3 — 여기에 실리는 것은 {@link SummaryRequest} 가 가진 것뿐이다.</b> 그 DTO 에는 회원
 * 식별정보를 담을 필드가 없다.
 */
public final class SummaryPrompt {

	private SummaryPrompt() {
	}

	/**
	 * 직전 요약과 병합 대상 턴들을 하나의 본문으로 만든다.
	 *
	 * <p><b>직전 요약이 먼저다.</b> 압축은 <b>이어 쓰는 일</b>이다 — 새 턴만 주면 모델은 그 앞의
	 * 이야기를 모른 채 요약하고, 그렇게 만들어진 요약이 다음 턴의 전제가 된다 (R4.5).
	 */
	public static String compose(SummaryRequest request) {
		StringBuilder body = new StringBuilder();

		if (request.previousSummary() != null && !request.previousSummary().isBlank()) {
			body.append("[지금까지의 줄거리]\n").append(request.previousSummary().strip()).append("\n\n");
		}

		body.append("[이어지는 턴]\n").append(request.turns().stream()
				.map(SummaryPrompt::line)
				.collect(Collectors.joining("\n")));
		return body.toString();
	}

	/**
	 * 턴 하나 (R4.7).
	 *
	 * <p>선택지 본문을 함께 넣는다 — <b>무엇을 골랐는가가 이야기의 분기</b>이며, 그것이 빠지면
	 * 요약은 "무슨 일이 있었나"만 남고 "왜 그렇게 됐나"를 잃는다.
	 */
	private static String line(SummaryRequest.TurnDigest turn) {
		String chosen = (turn.chosenChoiceText() != null && !turn.chosenChoiceText().isBlank())
				? " (선택: " + turn.chosenChoiceText().strip() + ")"
				: "";
		return turn.turnNo() + "턴" + chosen + ": " + turn.paragraphsDigest().strip();
	}
}

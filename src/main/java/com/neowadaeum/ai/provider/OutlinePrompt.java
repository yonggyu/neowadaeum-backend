package com.neowadaeum.ai.provider;

/**
 * 초안 호출에 실을 본문 (R7.14, B-52).
 *
 * <p><b>어댑터마다 따로 만들지 않는다.</b> {@link SummaryPrompt} 와 같은 이유다 — 무엇을 재료로
 * 넘기는가는 요구사항이 정한 것이지 벤더 사정이 아니다. 갈라지면 <b>같은 세계관이 provider 에
 * 따라 다른 초안</b>을 받는다.
 *
 * <p><b>I-3 — 여기 실리는 것은 {@link OutlineRequest} 가 가진 것뿐이다.</b> 그 DTO 에는 회원
 * 식별정보를 담을 필드가 없고, 원고 id 도 없다.
 *
 * <p><b>세계관을 지시와 같은 평면에 두지 않는다.</b> 이 문자열은 {@code user} 로 가고 형식
 * 지시는 {@code system} 으로 간다 ({@link com.neowadaeum.ai.prompt.PlatformPrompts#OUTLINE}) —
 * 세계관은 <b>작성자가 쓴 글</b>이며 그것이 플랫폼 레이어를 덮어쓸 수 없다 (I-7).
 */
public final class OutlinePrompt {

	private OutlinePrompt() {
	}

	/**
	 * 세계관과 요청 개수를 하나의 본문으로 만든다.
	 *
	 * <p><b>개수를 본문에 적는다.</b> 지시 레이어에 박으면 상수가 되고, 개수는 호출자가 정하는
	 * 값이다 (R7.14 의 챕터 5 · 엔딩 3 은 {@code OutlineController} 가 갖는다).
	 *
	 * <p><b>부탁이지 계약이 아니다.</b> 모델이 이보다 적게 주는 것은 어긋난 것이 아니며, 그래서
	 * 개수 부족은 재요청 사유가 아니다 (#238).
	 */
	public static String compose(OutlineRequest request) {
		return """
				[세계관]
				%s

				[요청]
				챕터 초안 %d개, 엔딩 초안 %d개.""".formatted(
				request.worldPrompt().strip(), request.chapterCount(), request.endingCount());
	}
}

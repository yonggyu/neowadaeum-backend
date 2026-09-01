package com.neowadaeum.play.port;

/**
 * 오래된 턴을 압축하는 계약 (R4.5, R4.6, B-34).
 *
 * <p><b>{@code play} 가 소유하고 {@code ai} 가 구현한다</b> (ADR-0006). {@link TurnGenerationPort}
 * 와 같은 방향이며 이유도 같다 — 요약을 언제 만들고 어디에 저장할지 아는 쪽은 {@code play} 이고,
 * 반대로 뒤집으면 {@code play → ai} 참조가 생겨 경계가 무너진다.
 */
public interface SummarizationPort {

	/**
	 * 압축된 줄거리를 돌려준다.
	 *
	 * <p><b>사용자 대기 시간 밖에서 불린다</b> (R4.6). 그렇다고 상한이 없는 것은 아니다 — 비용은
	 * 같은 곳에서 나가며, 시간 제한은 {@code ai} 의 데코레이터가 건다.
	 *
	 * @throws ProviderCallFailedException 호출이 실패했다. <b>턴은 이미 응답된 뒤</b>이므로 호출자는
	 *         이것으로 턴을 실패시키지 않는다 — 다음 턴에 다시 시도한다
	 */
	String summarize(SummaryRequest request);
}

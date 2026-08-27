package com.neowadaeum.play.debug;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 한 세션의 현재 상태 — 관리자가 보는 것 (§14 Debug).
 *
 * <p><b>{@code playerRef} 가 없다</b> (I-3). 관리자가 알아야 하는 것은 <b>세션에서 무슨 일이
 * 일어났는가</b>이지 그것이 누구인지가 아니다 — 담으면 그 화면이 회원 조회 도구가 된다.
 *
 * <p>{@code gameState} 와 {@code storySummary} 는 저장된 JSON·텍스트 그대로다. 여기서 해석하지
 * 않는다 — 디버그는 <b>저장된 것</b>을 보는 자리이고, 다시 가공하면 무엇이 저장돼 있었는지가
 * 흐려진다.
 *
 * @param turnNo 지금 화면에 떠 있는 턴 (§4.3 의 요청 {@code turnNo} 와 같은 뜻)
 * @param recentTurns 최근 턴. 최신이 앞이다
 */
public record SessionDebugView(UUID sessionId, UUID storyId, UUID storyVersionId, String status,
		String providerId, String modelId, int turnNo, int chapterNo, boolean testSession,
		String gameState, String storySummary, Integer summaryUptoTurnNo, List<TurnView> recentTurns,
		Instant createdAt, Instant updatedAt) {

	/**
	 * 턴 한 건.
	 *
	 * @param safetyVerdict 세이프티 판정 (R9.3). 관리자가 <b>왜 막혔는지</b>를 보는 자리다
	 * @param adminFreeInput 관리자 자유입력으로 만들어진 턴인가 (R14.2)
	 */
	public record TurnView(int turnNo, int chapterNo, String speakerName, String paragraphs,
			String choices, String chosenChoiceId, String safetyVerdict, boolean adminFreeInput,
			boolean ending, Instant createdAt) {
	}
}

package com.neowadaeum.play.orchestrator;

import com.neowadaeum.common.spi.SafetyCategory;
import java.util.Set;
import java.util.UUID;

/**
 * 한 턴의 처리 결과 (§4.3 의 11~12 단계 사이).
 *
 * <p><b>I-2 — 차단된 턴에는 본문이 담기지 않는다.</b> L2 를 통과한 경우에만 {@code turnId} 가 있고,
 * 차단이면 {@code null} 이다. 응답 조립(S-9-2)이 실수로 본문을 꺼낼 자리를 만들지 않는다.
 *
 * <p><b>R9.6 — 차단 사유를 사용자에게 구체적으로 표시하지 않는다.</b> {@code blockedCategories} 는
 * 서버 내부 기록용이며(R9.3), 응답으로 나가서는 안 된다. 여기 담는 이유는 {@code ai_call_log}
 * (B-25)와 관측(B-48)이 그것을 필요로 하기 때문이다.
 *
 * @param status            처리 결과
 * @param turnId            저장된 턴. 차단이면 {@code null}
 * @param turnNo            생성된 턴 번호. 차단이면 직전 턴 번호 그대로다 (R6.6)
 * @param chapterChanged    이 턴에서 챕터가 바뀌었는가 (R7.3)
 * @param chapterNo         판정 후 챕터
 * @param endingId          도달한 엔딩. 없으면 {@code null}
 * @param endingIndex       비시크릿 기준 순번. 시크릿 엔딩이면 {@code null} (R7.11)
 * @param totalEndings      비시크릿 엔딩 수 (R7.11)
 * @param blockedCategories 차단·재생성 사유. <b>서버 내부용</b>
 */
public record TurnOutcome(
		TurnStatus status,
		UUID turnId,
		int turnNo,
		boolean chapterChanged,
		int chapterNo,
		UUID endingId,
		Integer endingIndex,
		int totalEndings,
		Set<SafetyCategory> blockedCategories) {

	public TurnOutcome {
		blockedCategories = Set.copyOf(blockedCategories == null ? Set.of() : blockedCategories);
	}

	public boolean ended() {
		return this.status == TurnStatus.ENDED;
	}

	/** 턴 처리 결과의 갈래. */
	public enum TurnStatus {

		/** 정상 생성. 다음 선택지가 있다. */
		GENERATED,

		/** 엔딩에 도달했다 (R7.8). 선택지가 없다 */
		ENDED,

		/** 세이프티 차단 (§9.2). <b>세션 상태가 변하지 않는다</b> (R6.6) */
		SAFETY_BLOCKED
	}
}

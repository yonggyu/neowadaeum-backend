package com.neowadaeum.ai.debug;

import java.time.Instant;
import java.util.UUID;

/**
 * AI 호출 한 건 — <b>원문을 포함한다</b> (§14 Debug, R12.3).
 *
 * <p><b>{@code playerRef} 가 없다</b> (I-3). 담을 자리가 없는 것은 엔티티와 같은 성질이며,
 * 세션으로만 역추적한다.
 *
 * <p>이 값을 <b>로그로 찍지 않는다</b> (S-3). 원문 보관처는 {@code ai_call_log} 뿐이라는 규칙은
 * 꺼내 온 뒤에도 유효하다 — 응답으로 나가는 것과 로그에 남는 것은 다른 문제다.
 *
 * @param costMicroKrw 호출 비용, <b>원(KRW)의 백만분의 1</b> (#311, §13-53). 단가 설정이 없으면
 *                     {@code null} 이다
 * @param safetyFlags 세이프티 판정 결과 (R9.3). 통과면 빈 배열 문자열이다
 * @param attemptNo 재요청은 같은 턴의 별도 호출이다 (R5.8). 1부터 센다
 */
public record AiCallView(UUID id, String purpose, String providerId, String modelId,
		String fallbackFrom, String requestRaw, String responseRaw, Integer inputTokens,
		Integer outputTokens, Integer latencyMs, Long costMicroKrw, String safetyFlags, int attemptNo,
		Instant createdAt) {
}

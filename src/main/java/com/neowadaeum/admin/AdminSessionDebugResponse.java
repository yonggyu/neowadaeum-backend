package com.neowadaeum.admin;

import com.neowadaeum.ai.debug.AiCallView;
import com.neowadaeum.play.debug.SessionDebugView;
import java.util.List;

/**
 * Debug 응답 (§14).
 *
 * <p><b>두 모듈이 준 것을 나란히 둘 뿐 섞지 않는다.</b> 합쳐서 평평한 하나로 만들면 어느 쪽이
 * 무엇을 준 것인지가 사라지고, 한쪽이 바뀔 때 다른 쪽 필드까지 흔들린다.
 *
 * @param session 세션의 현재 상태 — provider · model · gameState · summary · 최근 턴
 * @param aiCalls AI 호출 원문과 사용량. <b>최신이 앞이다</b>
 */
public record AdminSessionDebugResponse(SessionDebugView session, List<AiCallView> aiCalls) {
}

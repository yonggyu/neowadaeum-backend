package com.neowadaeum.authoring.api;

import java.util.UUID;

/**
 * 열린 미리보기 세션 (§13.8, R8.13).
 *
 * <p><b>상한을 함께 알린다.</b> 모르면 작성자는 네 번째 턴에서 <b>왜 막혔는지</b>를 403 으로
 * 처음 겪는다.
 *
 * @param turnNo 만들어진 첫 턴
 */
public record PreviewResponse(UUID sessionId, int turnNo, int turnLimit) {
}

package com.neowadaeum.admin;

import jakarta.validation.constraints.Min;

/**
 * 어디까지 되돌릴 것인가 (§14).
 *
 * @param toTurnNo 되돌린 뒤 <b>남아 있을</b> 마지막 턴. {@code 0} 은 첫 턴까지 접는다는 뜻이며
 *     허용한다 — 첫 턴부터 잘못된 세션이 있다
 */
public record RollbackRequest(@Min(0) int toTurnNo) {
}

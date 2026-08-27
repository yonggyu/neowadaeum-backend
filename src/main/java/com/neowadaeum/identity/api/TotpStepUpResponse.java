package com.neowadaeum.identity.api;

/**
 * 두 번째 요소를 통과했다는 증표 (B-40, S-4).
 *
 * @param stepUpToken 관리자 요청에 {@code X-Admin-Step-Up} 으로 함께 보낸다
 * @param expiresIn 초. 모르면 만료를 403 으로 처음 겪고, 그때는 하던 작업이 이미 끊긴 뒤다
 */
public record TotpStepUpResponse(String stepUpToken, long expiresIn) {
}

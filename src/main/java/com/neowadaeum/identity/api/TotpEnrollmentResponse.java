package com.neowadaeum.identity.api;

/**
 * 등록을 시작하며 <b>한 번만</b> 나가는 값.
 *
 * <p>다시 보여 줄 수 있는 경로가 있으면 <b>그 경로가 곧 비밀 유출 경로</b>다.
 *
 * @param secret 인증기에 직접 입력하는 표기 (Base32)
 * @param otpauthUri QR 로 만들 표기. 같은 비밀을 담고 있다
 */
public record TotpEnrollmentResponse(String secret, String otpauthUri) {
}

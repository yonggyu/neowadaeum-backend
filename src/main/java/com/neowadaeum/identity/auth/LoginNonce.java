package com.neowadaeum.identity.auth;

/**
 * 발급된 로그인 nonce 한 벌 (§13-87).
 *
 * <p><b>값과 수명이 함께 다닌다.</b> 값만 돌려주면 부르는 쪽은 언제까지 쓸 수 있는지 모르고,
 * GIS 왕복이 길어진 뒤 <b>이미 만료된 값으로 로그인을 시도</b>하게 된다 — 그 실패는 401 이라
 * 사용자에게는 그냥 "로그인이 안 된다"로 보인다.
 *
 * <p><b>회원과 묶이지 않는다.</b> 로그인 <b>전</b>이라 묶을 회원이 없고, 그래서 여기에도
 * {@link LoginNonceStore} 의 키에도 식별정보가 들어갈 자리가 없다 (I-3).
 *
 * @param value            ID 토큰의 {@code nonce} 클레임에 실릴 값
 * @param expiresInSeconds 이 값이 유효한 남은 초
 */
public record LoginNonce(String value, long expiresInSeconds) {
}

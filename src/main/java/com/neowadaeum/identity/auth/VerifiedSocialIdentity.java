package com.neowadaeum.identity.auth;

/**
 * ID 토큰 검증을 통과한 소셜 계정 (§2.2).
 *
 * <p><b>이메일 원문이 여기에 없다.</b> 검증기가 해시로 바꿔서 내보내므로, 이 타입을 받는 쪽은
 * 원문을 볼 방법이 없다 — 새어 나갈 값 자체를 경계 밖으로 내보내지 않는 것이 I-3 를 구조로
 * 지키는 방법이다.
 *
 * @param subject   provider 가 발급한 계정 식별자. provider 안에서만 유일하다
 * @param emailHash 이메일의 SHA-256. 토큰에 이메일이 없으면 {@code null}
 */
public record VerifiedSocialIdentity(String subject, String emailHash) {
}

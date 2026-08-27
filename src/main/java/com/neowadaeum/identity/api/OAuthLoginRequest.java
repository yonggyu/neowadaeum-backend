package com.neowadaeum.identity.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 소셜 로그인 요청 (§13-22 의 {@code OAuthLoginRequest}).
 *
 * <p><b>{@code birthDate} 와 {@code consents} 는 아직 없다.</b> 계약에는 선택 필드로 존재하지만
 * 그 값을 <b>판정할 코드가 B-13</b> 이다. 지금 받아 두면 클라이언트가 보낸 생년월일이 아무 검사
 * 없이 통과한 것처럼 보이고, 그 상태가 가장 위험하다 — 없는 편이 낫다.
 *
 * @param idToken provider 가 발급한 ID 토큰. <b>서버가 검증한다</b>
 */
public record OAuthLoginRequest(@NotBlank String idToken) {
}
